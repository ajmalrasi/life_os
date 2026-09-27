from __future__ import annotations

import logging
from calendar import monthrange
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Response
from sqlalchemy.orm import Session

from app.database import SessionLocal, get_db
from app.repositories.analytics_repo import (
    DailyBriefRepository,
    InsightRepository,
    ScoreRepository,
    StateRepository,
    SynthesizedInsightRepository,
    WeeklyRecapRepository,
)
from app.schemas.analytics import (
    DailyScoreOut,
    DailyStateOut,
    InsightOut,
    RecomputeResult,
    SynthesizedInsightOut,
    TodayBriefOut,
    TodayOut,
    WeeklyRecapOut,
)
from app.config import get_settings
from app.repositories.device_repo import DeviceRepository
from app.services import day_brief, insight_synthesizer
from app.repositories.health_repo import HealthRepository
from app.services.analytics import AnalyticsService
from app.services.execution_sync import run_execution_sync
from app.services.fcm import FcmService
from app.services.job_guard import DAY_BRIEF_JOB, INSIGHTS_JOB, WEEKLY_RECAP_JOB, try_job_lock
from app.services.life_evidence import build_life_evidence
from app.services.notifications import Notification, NotificationService
from app.services.weekly_recap import week_bounds, build_recap

router = APIRouter(prefix="/analytics", tags=["analytics"])
logger = logging.getLogger("lifeos.analytics")

# GitHub execution only changes for very recent days; scanning a long window on
# every sync is wasteful. The background scan covers just this trailing window.
EXECUTION_SYNC_DAYS = 7


@router.post("/recompute", response_model=RecomputeResult)
def recompute(
    background_tasks: BackgroundTasks,
    days: int = 60,
    scan_execution: bool = True,
    db: Session = Depends(get_db),
) -> RecomputeResult:
    """Recompute scores + states for the trailing window and rediscover patterns.

    Triggered after each sync so the dashboard is current. The health/score
    recompute (sleep, recovery, etc. — what the user is waiting on) runs
    synchronously and returns fast. The GitHub execution scan (44 repos) is slow
    and only affects execution scores, so it runs in the background and refreshes
    execution data for the *next* recompute. FCM push is best-effort.
    ``scan_execution=false`` skips that scan for a caller (the app's Sync
    everything) that already ran ``/collectors/execution/sync`` itself.
    """
    settings = get_settings()
    end = date.today()
    start = end - timedelta(days=days - 1)
    scored, states, insights = AnalyticsService(db).run_nightly(start, end)

    _maybe_push(db, settings)

    if scan_execution:
        background_tasks.add_task(_sync_execution_bg, EXECUTION_SYNC_DAYS)
    background_tasks.add_task(refresh_day_brief_bg)

    return RecomputeResult(
        days_scored=scored,
        states_classified=states,
        insights_found=insights,
        execution_days_updated=0,
        execution_repos_scanned=0,
        execution_today_commits=0,
        execution_today_coding_minutes=0,
        execution_message="execution sync running in background",
    )


def _sync_execution_bg(days: int) -> None:
    """Scan GitHub for execution off the request path, then re-score those days.

    Uses its own DB session (the request's session is closed once the response
    is sent). Best-effort — failures are logged, never surfaced to the client.
    """
    settings = get_settings()
    db = SessionLocal()
    try:
        run_execution_sync(db, settings, days=days)
        end = date.today()
        start = end - timedelta(days=days - 1)
        AnalyticsService(db).run_nightly(start, end)
    except Exception:
        logger.exception("background execution sync failed")
    finally:
        db.close()


def refresh_day_brief_bg() -> None:
    """Rewrite today's brief if its facts changed. Off the request path, one at a
    time; a refresh already running covers this one."""
    db = SessionLocal()
    try:
        with try_job_lock(db.get_bind(), DAY_BRIEF_JOB) as acquired:
            if acquired:
                day_brief.generate_brief(db, _today_ist())
    except Exception:
        logger.exception("day brief refresh failed")
    finally:
        db.close()


def _today_ist() -> date:
    return datetime.now(ZoneInfo("Asia/Kolkata")).date()


def _maybe_push(db: Session, settings) -> None:
    """Deliver high-priority notifications via FCM. Never raises into the request."""
    if not settings.fcm_enabled:
        return
    try:
        notifications = NotificationService(db).generate()
        tokens = DeviceRepository(db).all_tokens()
        FcmService(settings).push_notifications(notifications, tokens)
    except Exception:  # push is best-effort; analysis already succeeded
        pass


@router.get("/scores", response_model=list[DailyScoreOut])
def get_scores(days: int = 30, db: Session = Depends(get_db)) -> list[DailyScoreOut]:
    end = date.today()
    start = end - timedelta(days=days - 1)
    rows = ScoreRepository(db).range(start, end)
    return [DailyScoreOut.model_validate(r) for r in rows]


@router.get("/scores/{day}", response_model=DailyScoreOut)
def get_score(day: date, db: Session = Depends(get_db)) -> DailyScoreOut:
    row = ScoreRepository(db).get(day)
    if row is None:
        raise HTTPException(status_code=404, detail="No scores for that day (run /analytics/recompute)")
    return DailyScoreOut.model_validate(row)


@router.get("/state", response_model=list[DailyStateOut])
def get_states(days: int = 30, db: Session = Depends(get_db)) -> list[DailyStateOut]:
    end = date.today()
    start = end - timedelta(days=days - 1)
    rows = StateRepository(db).range(start, end)
    return [DailyStateOut.model_validate(r) for r in rows]


@router.get("/today", response_model=TodayOut)
def get_today(background_tasks: BackgroundTasks, db: Session = Depends(get_db)) -> TodayOut:
    """Today's model-written brief plus when health data last arrived.

    Never waits on the model. If the facts changed since the stored brief was
    written, the previous brief is returned as ``refreshing`` and a rewrite
    starts in the background; poll again in ~20 s.
    """
    day = _today_ist()
    brief = DailyBriefRepository(db).get(day)
    if not get_settings().insight_synthesis_enabled:
        status = "unavailable"
    elif day_brief.is_current(db, day, brief):
        status = "ready"
    else:
        status = "refreshing"
        background_tasks.add_task(refresh_day_brief_bg)
    return TodayOut(
        day=day,
        brief=TodayBriefOut(
            state=brief.state,
            confidence=brief.confidence,
            headline=brief.headline,
            summary=brief.summary,
            suggestion=brief.suggestion,
            workout_note=brief.workout_note,
            workout_attention=bool(brief.workout_attention),
            generated_at=brief.generated_at,
        ) if brief is not None else None,
        brief_status=status,
        health_last_received_at=HealthRepository(db).last_received_at(),
    )


@router.get("/insights", response_model=list[InsightOut])
def get_insights(db: Session = Depends(get_db)) -> list[InsightOut]:
    rows = InsightRepository(db).all()
    return [InsightOut.model_validate(r) for r in rows]


def _to_synthesis_out(r) -> SynthesizedInsightOut:
    return SynthesizedInsightOut(
        title=r.title,
        body=r.body,
        theme=r.theme,
        action=r.action,
        severity=r.severity,
        cites=[c for c in r.cites.split(",") if c],
    )


@router.get("/synthesis", response_model=list[SynthesizedInsightOut])
def get_synthesis(db: Session = Depends(get_db)) -> list[SynthesizedInsightOut]:
    """LLM-synthesized, mentor-style insights grounded in cited evidence."""
    rows = SynthesizedInsightRepository(db).all()
    return [_to_synthesis_out(r) for r in rows]


@router.post("/synthesis/refresh", response_model=list[SynthesizedInsightOut])
def refresh_synthesis(response: Response, db: Session = Depends(get_db)) -> list[SynthesizedInsightOut]:
    """Rebuild evidence and regenerate the mentor insights right now.

    Guarded by a singleton lock like the weekly recap: a racing background
    recompute and a user-triggered pull-to-refresh must never overlap. On a
    collision, or when synthesis comes back empty (model down/disabled/
    unparseable), the current rows are returned with ``X-Insights-Stale: 1``
    rather than an error — the caller always has something to show.
    """
    repo = SynthesizedInsightRepository(db)
    with try_job_lock(db.get_bind(), INSIGHTS_JOB) as acquired:
        if not acquired:
            response.headers["X-Insights-Stale"] = "1"
            return [_to_synthesis_out(r) for r in repo.all()]

        evidence = build_life_evidence(db)
        today = datetime.now(ZoneInfo("Asia/Kolkata")).date()
        today_label = f"Today is {today.isoformat()} (day {today.day} of {monthrange(today.year, today.month)[1]} in the month)."
        drafts = insight_synthesizer.synthesize(evidence, today_label=today_label)
        rows = repo.replace(drafts)
        if not drafts:
            response.headers["X-Insights-Stale"] = "1"
        return [_to_synthesis_out(r) for r in rows]


@router.get("/weekly-recap", response_model=WeeklyRecapOut)
def get_weekly_recap(db: Session = Depends(get_db)) -> WeeklyRecapOut:
    row = WeeklyRecapRepository(db).latest()
    if row is None:
        raise HTTPException(status_code=404, detail="No weekly recap yet")
    return WeeklyRecapOut(week_start=row.week_start, week_end=row.week_end, prose=row.prose)


@router.post("/weekly-recap", response_model=WeeklyRecapOut)
def post_weekly_recap(db: Session = Depends(get_db)) -> WeeklyRecapOut:
    """Compute and store this week's recap, then push it via FCM.

    Guarded by a singleton lock: the weekly systemd timer and a manual trigger
    racing each other must never write two rows or double-push a notification.
    """
    settings = get_settings()
    with try_job_lock(db.get_bind(), WEEKLY_RECAP_JOB) as acquired:
        if not acquired:
            existing = WeeklyRecapRepository(db).latest()
            if existing is None:
                raise HTTPException(status_code=409, detail="Weekly recap generation already in progress")
            return WeeklyRecapOut(week_start=existing.week_start, week_end=existing.week_end, prose=existing.prose)

        prose = build_recap(db)
        if prose is None:
            raise HTTPException(status_code=404, detail="Not enough data yet for a weekly recap")

        prev_start, prev_end, start, end = week_bounds(date.today())
        row = WeeklyRecapRepository(db).upsert(week_start=start, week_end=end, prose=prose)

    if settings.fcm_enabled:
        try:
            tokens = DeviceRepository(db).all_tokens()
            notification = Notification(severity="info", title="Your week in review", detail=prose)
            FcmService(settings).push_notifications([notification], tokens)
        except Exception:  # push is best-effort; the recap already saved
            pass

    return WeeklyRecapOut(week_start=row.week_start, week_end=row.week_end, prose=row.prose)
