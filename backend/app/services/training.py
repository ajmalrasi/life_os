"""Training workouts, told apart from walking.

Apple Watch logs both deliberate training (strength, cycling, running...) and
walks as workouts. Walking already counts through steps, so everything that
judges exercise (the fitness score, the Today brief, Insights, the weekly
recap) uses these helpers and never counts a walk as training.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta
from zoneinfo import ZoneInfo

from app.models.health import HealthRecord
from app.repositories.health_repo import HealthRepository

IST = ZoneInfo("Asia/Kolkata")


def is_walk_workout(label: str | None) -> bool:
    """A walk or hike logged as a workout is still walking, not training."""
    return bool(label) and any(word in label.lower() for word in ("walk", "hik"))


def workout_day(w: HealthRecord) -> date:
    start = w.start_time
    return (start.astimezone(IST) if start.tzinfo else start).date()


def training_minutes(health: HealthRepository, day: date) -> float | None:
    """Training minutes on ``day``; 0 when only walks were logged, None when no workouts."""
    workouts = health.workouts_between(day, day)
    if not workouts:
        return None
    return sum(w.value for w in workouts if not is_walk_workout(w.label))


@dataclass
class TrainingSummary:
    last: HealthRecord | None
    days_since_last: int | None
    sessions_last_28: int
    minutes_last_28: float
    sessions_prev_28: int
    sessions_last_7: int
    sessions_prev_7: int
    # False until the phone has sent a workouts export: absence is then unknown.
    workouts_synced: bool

    @property
    def last_label(self) -> str:
        return (self.last.label or "Workout") if self.last else ""


def training_summary(health: HealthRepository, today: date) -> TrainingSummary:
    history = [w for w in health.workouts_between(date(1970, 1, 1), today) if not is_walk_workout(w.label)]

    def between(start: date, end: date) -> list[HealthRecord]:
        return [w for w in history if start <= workout_day(w) <= end]

    last_28 = between(today - timedelta(days=27), today)
    last = history[-1] if history else None
    return TrainingSummary(
        last=last,
        days_since_last=(today - workout_day(last)).days if last else None,
        sessions_last_28=len(last_28),
        minutes_last_28=sum(w.value for w in last_28),
        sessions_prev_28=len(between(today - timedelta(days=55), today - timedelta(days=28))),
        sessions_last_7=len(between(today - timedelta(days=6), today)),
        sessions_prev_7=len(between(today - timedelta(days=13), today - timedelta(days=7))),
        workouts_synced=health.workout_export_seen_at() is not None,
    )


def training_statement(s: TrainingSummary, today: date) -> str:
    """One plain sentence set about training for grounded model prompts."""
    parts: list[str] = []
    if s.last is not None:
        d = workout_day(s.last)
        parts.append(
            f"Last training workout: {s.last_label}, {s.last.value:.0f} min on "
            f"{d:%b} {d.day}, {s.days_since_last} days ago."
        )
    else:
        parts.append("No training workout has ever been recorded.")
    parts.append(
        f"Training sessions: {s.sessions_last_28} in the last 28 days "
        f"({s.minutes_last_28:.0f} min), {s.sessions_prev_28} in the 28 days before."
    )
    if not s.workouts_synced:
        parts.append("Workouts are not synced from the phone yet, so recent training is unknown, not zero.")
    parts.append("Walks are not counted as training.")
    return " ".join(parts)
