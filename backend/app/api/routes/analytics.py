from __future__ import annotations

import logging
from datetime import date, timedelta

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException
from sqlalchemy.orm import Session

from app.database import SessionLocal, get_db
from app.repositories.analytics_repo import (
    InsightRepository,
    ScoreRepository,
    StateRepository,
)
from app.schemas.analytics import (
    DailyScoreOut,
    DailyStateOut,
    InsightOut,
    RecomputeResult,
)
from app.config import get_settings
from app.repositories.device_repo import DeviceRepository
from app.services.analytics import AnalyticsService
from app.services.execution_sync import run_execution_sync
from app.services.fcm import FcmService
from app.services.notifications import NotificationService

router = APIRouter(prefix="/analytics", tags=["analytics"])
logger = logging.getLogger("lifeos.analytics")

# GitHub execution only changes for very recent days; scanning a long window on
# every sync is wasteful. The background scan covers just this trailing window.
EXECUTION_SYNC_DAYS = 7


@router.post("/recompute", response_model=RecomputeResult)
def recompute(
    background_tasks: BackgroundTasks,
    days: int = 60,
    db: Session = Depends(get_db),
) -> RecomputeResult:
    """Recompute scores + states for the trailing window and rediscover patterns.

    Triggered after each sync so the dashboard is current. The health/score
    recompute (sleep, recovery, etc. — what the user is waiting on) runs
    synchronously and returns fast. The GitHub execution scan (44 repos) is slow
    and only affects execution scores, so it runs in the background and refreshes
    execution data for the *next* recompute. FCM push is best-effort.
    """
    settings = get_settings()
    end = date.today()
    start = end - timedelta(days=days - 1)
    scored, states, insights = AnalyticsService(db).run_nightly(start, end)

    _maybe_push(db, settings)

    background_tasks.add_task(_sync_execution_bg, EXECUTION_SYNC_DAYS)

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


@router.get("/insights", response_model=list[InsightOut])
def get_insights(db: Session = Depends(get_db)) -> list[InsightOut]:
    rows = InsightRepository(db).all()
    return [InsightOut.model_validate(r) for r in rows]
