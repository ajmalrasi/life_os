"""Workouts, described in full so the model can judge them.

Every workout the watch logs counts, walks included. What code never decides
is whether a workout was *real* exercise: a 30-second session every day is not
training, but no fixed threshold captures that well. So these helpers state
each session plainly (type, duration, active energy, average heart rate) and
the model makes the call, in the Today brief, Insights and the weekly recap.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta
from zoneinfo import ZoneInfo

from app.repositories.health_repo import HealthRepository, WorkoutSession

IST = ZoneInfo("Asia/Kolkata")
# Sessions listed individually in a prompt; older ones are summarised by count.
_MAX_LISTED = 12


def workout_day(w) -> date:
    start = w.start_time
    return (start.astimezone(IST) if start.tzinfo else start).date()


def workout_minutes(health: HealthRepository, day: date) -> float | None:
    """All workout minutes on ``day``; None when no workout was logged."""
    workouts = health.workouts_between(day, day)
    return sum(w.value for w in workouts) if workouts else None


def describe_session(w: WorkoutSession) -> str:
    start = w.start_time.astimezone(IST) if w.start_time.tzinfo else w.start_time
    bits = [f"{start:%b} {start.day} {start:%H:%M} {w.label}, {w.minutes:.0f} min"]
    if w.energy_kcal is not None:
        bits.append(f"{w.energy_kcal:.0f} kcal active")
    if w.avg_hr is not None:
        bits.append(f"avg HR {w.avg_hr:.0f} bpm")
    return ", ".join(bits)


@dataclass
class WorkoutSummary:
    recent: list[WorkoutSession]          # last 28 days, oldest first
    sessions_prev_28: int
    last: WorkoutSession | None           # most recent ever, even if older than 28 days
    days_since_last: int | None
    # The latest session before the 28-day window, so a lone recent walk never
    # hides how long ago the last real session was.
    before_window: WorkoutSession | None
    days_since_before_window: int | None
    sessions_last_7: int
    sessions_prev_7: int
    # False until the phone has sent a workouts export: absence is then unknown.
    workouts_synced: bool


def workout_summary(health: HealthRepository, today: date) -> WorkoutSummary:
    window_start = today - timedelta(days=27)
    recent = health.workout_sessions(window_start, today)
    prev = health.workout_sessions(today - timedelta(days=55), today - timedelta(days=28))
    before = None
    older = health.latest_workout(window_start - timedelta(days=1))
    if older is not None:
        day = workout_day(older)
        before = next((s for s in reversed(health.workout_sessions(day, day))), None)
    last = recent[-1] if recent else before
    return WorkoutSummary(
        before_window=before,
        days_since_before_window=(today - workout_day(before)).days if before else None,
        recent=recent,
        sessions_prev_28=len(prev),
        last=last,
        days_since_last=(today - workout_day(last)).days if last else None,
        sessions_last_7=sum(1 for s in recent if workout_day(s) >= today - timedelta(days=6)),
        sessions_prev_7=sum(
            1 for s in recent if today - timedelta(days=13) <= workout_day(s) <= today - timedelta(days=7)
        ),
        workouts_synced=health.workout_export_seen_at() is not None,
    )


def workout_statement(s: WorkoutSummary) -> str:
    """Every recent session with its effort, for the model to judge."""
    parts: list[str] = []
    if s.recent:
        listed = s.recent[-_MAX_LISTED:]
        parts.append(
            f"Workouts in the last 28 days ({len(s.recent)}): "
            + "; ".join(describe_session(w) for w in listed) + "."
        )
        if len(s.recent) > len(listed):
            parts.append(f"{len(s.recent) - len(listed)} earlier sessions in that window are not listed.")
        if s.before_window is not None:
            parts.append(
                f"Latest workout before that window: {describe_session(s.before_window)}, "
                f"{s.days_since_before_window} days ago."
            )
    else:
        parts.append("No workouts in the last 28 days.")
        if s.last is not None:
            parts.append(f"Last workout: {describe_session(s.last)}, {s.days_since_last} days ago.")
        else:
            parts.append("No workout has ever been recorded.")
    parts.append(f"Workouts in the 28 days before that: {s.sessions_prev_28}.")
    if not s.workouts_synced:
        parts.append("Workouts are not synced from the phone yet, so recent workouts are unknown, not zero.")
    return " ".join(parts)


# How the model should read workouts. Shared by every prompt that sees them.
WORKOUT_JUDGEMENT_RULE = (
    "Judge workouts by their substance, not their count: use each session's duration, "
    "active energy and heart rate. A few minutes or very little energy is not real "
    "exercise, even if it happens every day. Weeks without a substantial workout "
    "matter more than a day's HRV or sleep dip."
)
