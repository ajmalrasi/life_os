"""Weekly recap: deterministic numbers first, Qwen only writes the prose.

Same evidence-bound shape as ``insight_synthesizer`` — every number in the
recap is computed here, handed to the model as EVIDENCE, and the model is
asked to phrase it, never to invent a figure. If the model is disabled,
unreachable, or returns something unusable, ``build_recap`` falls back to a
deterministic paragraph joining the same facts, so a recap is always produced
from real data — the LLM step is a nicety, never a requirement.
"""

from __future__ import annotations

import json
import logging
from datetime import date, timedelta

from sqlalchemy.orm import Session

from app.repositories.health_repo import HealthRepository
from app.services.training import WORKOUT_JUDGEMENT_RULE, describe_session, workout_day, workout_summary
from app.api.routes.statements import build_categorizer
from app.config import get_settings
from app.repositories.analytics_repo import ScoreRepository
from app.repositories.expense_repo import ExpenseRepository
from app.repositories.mood_repo import MoodRepository
from app.services.budgets import budget_pace_summary
from app.services.insight_synthesizer import EvidenceFact
from app.services.openclaw import InferenceWorkload, OpenClawClient, OpenClawUnavailable

logger = logging.getLogger("lifeos.weekly_recap")
settings = get_settings()

_RECAP_TIMEOUT_SECONDS = 45.0

_SYSTEM_PROMPT = (
    "You are LifeOS, a personal-analytics companion. You are given EVIDENCE: a list "
    "of already-computed facts about ONE user's own last 7 days versus the 7 days "
    "before that — sleep, mood, workouts, spending, budgets. Each fact has an \"id\".\n\n"
    + WORKOUT_JUDGEMENT_RULE + "\n\n"
    "Write a short weekly recap: 2-4 sentences, warm but plain, no markdown, no "
    "emojis. Use ONLY the numbers given — never invent or round differently than "
    "the facts state. Do not claim one fact causes another; just report what "
    "happened this week versus last week.\n\n"
    "Return ONLY the recap text. No JSON, no preamble, no quotes around it."
)


def week_bounds(today: date) -> tuple[date, date, date, date]:
    """This week = the 7 days ending yesterday; last week = the 7 before that.

    Ending at yesterday (not today) so a recap generated mid-day never reports
    a partial "today" as if it were a complete day.
    """
    end = today - timedelta(days=1)
    start = end - timedelta(days=6)
    prev_end = start - timedelta(days=1)
    prev_start = prev_end - timedelta(days=6)
    return prev_start, prev_end, start, end


def _avg(values: list[float]) -> float | None:
    vals = [v for v in values if v is not None]
    return sum(vals) / len(vals) if vals else None


def compute_weekly_facts(db: Session, today: date | None = None) -> list[EvidenceFact]:
    """Deterministic numbers only — this is the ground truth the prose must stay inside."""
    today = today or date.today()
    prev_start, prev_end, start, end = week_bounds(today)

    facts: list[EvidenceFact] = []

    scores = ScoreRepository(db)
    this_week_sleep = _avg([s.sleep_score for s in scores.range(start, end)])
    last_week_sleep = _avg([s.sleep_score for s in scores.range(prev_start, prev_end)])
    if this_week_sleep is not None:
        detail = f"Average sleep score this week: {this_week_sleep:.0f}"
        if last_week_sleep is not None:
            detail += f" (last week: {last_week_sleep:.0f})"
        facts.append(EvidenceFact(id="sleep", statement=detail))

    moods = MoodRepository(db)
    this_week_mood = _avg([m.mood for m in moods.range(start, end) if m.mood is not None])
    last_week_mood = _avg([m.mood for m in moods.range(prev_start, prev_end) if m.mood is not None])
    if this_week_mood is not None:
        detail = f"Average logged mood this week: {this_week_mood:.1f}/5"
        if last_week_mood is not None:
            detail += f" (last week: {last_week_mood:.1f}/5)"
        facts.append(EvidenceFact(id="mood", statement=detail))

    expenses = ExpenseRepository(db)
    spend_map = expenses.daily_spend(start, end)
    prev_spend_map = expenses.daily_spend(prev_start, prev_end)
    this_week_spend = sum(spend_map.values()) if spend_map else 0.0
    last_week_spend = sum(prev_spend_map.values()) if prev_spend_map else 0.0
    if spend_map or prev_spend_map:
        detail = f"Spending this week: Rs {this_week_spend:,.0f}"
        if prev_spend_map:
            delta = this_week_spend - last_week_spend
            direction = "more" if delta >= 0 else "less"
            detail += f" (Rs {abs(delta):,.0f} {direction} than last week's Rs {last_week_spend:,.0f})"
        facts.append(EvidenceFact(id="spend", statement=detail))

    try:
        summary = workout_summary(HealthRepository(db), end)
        if summary.last is not None or summary.workouts_synced:
            this_week = [w for w in summary.recent if workout_day(w) >= start]
            detail = f"Workouts this week: {summary.sessions_last_7} (last week: {summary.sessions_prev_7})"
            if this_week:
                detail += ": " + "; ".join(describe_session(w) for w in this_week)
            elif summary.days_since_last is not None:
                detail += f"; the last one was {summary.days_since_last} days ago ({describe_session(summary.last)})"
            facts.append(EvidenceFact(id="workouts", statement=detail))
    except Exception:  # workouts are a nicety in the recap, never a blocker
        logger.debug("weekly recap workout summary failed", exc_info=True)

    try:
        categorize = build_categorizer(db)
        paces = budget_pace_summary(db, categorize, today=today)
        exceeding = [p for p in paces if p.on_pace_to_exceed]
        if exceeding:
            names = ", ".join(p.category for p in exceeding[:3])
            detail = f"On pace to exceed this month's budget in: {names}"
            facts.append(EvidenceFact(id="budgets", statement=detail))
    except Exception:  # budgets are a nicety in the recap, never a blocker
        logger.debug("weekly recap budget pace failed", exc_info=True)

    return facts


def _fallback_prose(facts: list[EvidenceFact]) -> str:
    return " ".join(f.statement + "." for f in facts)


def build_recap(db: Session, client: OpenClawClient | None = None, today: date | None = None) -> str | None:
    """Return recap prose, or ``None`` if there's nothing worth reporting yet."""
    facts = compute_weekly_facts(db, today=today)
    if not facts:
        return None

    fallback = _fallback_prose(facts)
    if not settings.insight_synthesis_enabled:
        return fallback

    client = client or OpenClawClient(workload=InferenceWorkload.BACKGROUND)
    try:
        client.timeout = min(client.timeout, _RECAP_TIMEOUT_SECONDS)
    except Exception:  # client may be a stub in tests
        pass

    payload = [{"id": f.id, "fact": f.statement} for f in facts]
    messages = [
        {"role": "system", "content": _SYSTEM_PROMPT},
        {"role": "user", "content": "EVIDENCE (JSON):\n" + json.dumps(payload, indent=2)},
    ]
    try:
        raw = client.chat(messages, temperature=0.7)
    except OpenClawUnavailable as exc:
        logger.info("weekly recap narration skipped (OpenClaw unavailable): %s", exc)
        return fallback
    except Exception:
        logger.debug("weekly recap narration failed", exc_info=True)
        return fallback

    text = (raw or "").strip()
    if text.startswith("```"):
        text = text.strip("`").strip()
    # A too-short or clearly-empty reply is worse than the deterministic
    # fallback; never store nothing when we have real numbers to report.
    return text if len(text) >= 20 else fallback
