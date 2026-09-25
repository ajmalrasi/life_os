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


def describe_session(w: WorkoutSession, today: date | None = None) -> str:
    start = w.start_time.astimezone(IST) if w.start_time.tzinfo else w.start_time
    # The gap leads, so it is never confused with the session's minutes.
    ago = f" ({(today - start.date()).days} days ago)" if today is not None else ""
    bits = [f"{start:%b} {start.day}{ago} {start:%H:%M} {w.label}, {w.minutes:.0f} min"]
    if w.energy_kcal is not None:
        bits.append(f"{w.energy_kcal:.0f} kcal active")
    if w.avg_hr is not None:
        bits.append(f"avg HR {w.avg_hr:.0f} bpm")
    return ", ".join(bits)


# How far back the model sees workouts week by week: a quarter, long enough to
# show a trend (a routine, its decline, a gap) and to make one session in a
# quiet stretch read as an exception, not a routine.
WINDOW_WEEKS = 13


@dataclass
class WorkoutSummary:
    recent: list[WorkoutSession]          # last WINDOW_WEEKS weeks, oldest first
    weekly_counts: list[int]              # sessions per 7-day block, oldest first; last = the past 7 days
    weekly_minutes: list[float]
    last: WorkoutSession | None           # most recent ever, even if older than the window
    days_since_last: int | None
    # The latest session before the window, so a quiet stretch shows its full length.
    before_window: WorkoutSession | None
    sessions_last_7: int
    sessions_prev_7: int
    # False until the phone has sent a workouts export: absence is then unknown.
    workouts_synced: bool
    today: date


def workout_summary(health: HealthRepository, today: date) -> WorkoutSummary:
    window_start = today - timedelta(days=7 * WINDOW_WEEKS - 1)
    recent = health.workout_sessions(window_start, today)
    weekly = [0] * WINDOW_WEEKS
    minutes = [0.0] * WINDOW_WEEKS
    for w in recent:
        i = WINDOW_WEEKS - 1 - (today - workout_day(w)).days // 7
        weekly[i] += 1
        minutes[i] += w.minutes
    before = None
    older = health.latest_workout(window_start - timedelta(days=1))
    if older is not None:
        day = workout_day(older)
        before = next((s for s in reversed(health.workout_sessions(day, day))), None)
    last = recent[-1] if recent else before
    return WorkoutSummary(
        recent=recent,
        weekly_counts=weekly,
        weekly_minutes=minutes,
        last=last,
        days_since_last=(today - workout_day(last)).days if last else None,
        before_window=before,
        sessions_last_7=weekly[-1],
        sessions_prev_7=weekly[-2],
        workouts_synced=health.workout_export_seen_at() is not None,
        today=today,
    )


def workout_statement(s: WorkoutSummary) -> str:
    """Every session of the last weeks with its effort, and the weekly rhythm."""
    weeks = WINDOW_WEEKS
    parts: list[str] = []
    if s.recent:
        listed = s.recent[-_MAX_LISTED:]
        parts.append(
            f"Workouts in the last {weeks} weeks ({len(s.recent)}): "
            + "; ".join(describe_session(w, s.today) for w in listed) + "."
        )
        if len(s.recent) > len(listed):
            parts.append(
                f"{len(s.recent) - len(listed)} earlier sessions are not listed; the weekly figures include them."
            )
    else:
        parts.append(f"No workouts in the last {weeks} weeks.")
    parts.append(
        f"Week by week over the last {weeks} weeks, oldest to newest (the last is the past 7 days), "
        "as sessions/minutes: "
        + ", ".join(f"{n}/{m:.0f}" for n, m in zip(s.weekly_counts, s.weekly_minutes)) + "."
    )
    if s.last is not None:
        parts.append(f"Days since the most recent workout: {s.days_since_last}.")
    if s.before_window is not None:
        parts.append(f"Latest workout before those {weeks} weeks: {describe_session(s.before_window, s.today)}.")
    elif not s.recent:
        parts.append("No workout has ever been recorded.")
    if not s.workouts_synced:
        parts.append("Workouts are not synced from the phone yet, so recent workouts are unknown, not zero.")
    return " ".join(parts)


# How the model should read workouts. Shared by every prompt that sees them.
WORKOUT_JUDGEMENT_RULE = (
    "Judge workouts as a routine, not one session at a time. Every workout counts, "
    "walks included, and a session is worthwhile when its duration and energy are "
    "real (a few minutes or very little energy is not). What matters most is how "
    "often and how recently: use the weekly counts and the days since the last "
    "workout. Read the week-by-week figures as a trend: is there a routine, is it "
    "building or fading, when did it stop? One good session in several quiet weeks is "
    "not a routine; say how long the gaps were on both sides of it. Weeks without "
    "regular workouts matter more than a day's HRV or sleep dip."
)
