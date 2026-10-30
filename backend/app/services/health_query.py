"""Shared synced-health reads for Ask and the public API; no model or phone calls."""

from __future__ import annotations

from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from sqlalchemy.orm import Session

from app.models.health import HealthRecordType
from app.repositories.health_repo import HealthRepository
from app.services.training import workout_day

_MAX_DAYS = 90


def read_health_records(
    db: Session,
    days: int = 14,
    start_date: str | None = None,
    end_date: str | None = None,
) -> dict:
    """Synced Apple Health readings and workouts; never contacts the phone."""
    start, end = _window(days)
    if start_date or end_date:
        try:
            start = date.fromisoformat(start_date) if start_date else start
            end = date.fromisoformat(end_date) if end_date else end
        except ValueError:
            return {
                "error": "start_date and end_date must be ISO dates like 2026-07-01"
            }
        if end < start:
            return {"error": "end_date is before start_date"}
        end = min(end, datetime.now(ZoneInfo("Asia/Kolkata")).date())
        start = max(start, end - timedelta(days=_MAX_DAYS - 1))
        days = (end - start).days + 1
    health = HealthRepository(db)

    def total(d: date, t) -> float | None:
        vals = health.daily_values(d, t)
        return round(sum(vals), 1) if vals else None

    def mean(d: date, t) -> float | None:
        vals = health.daily_values(d, t)
        return round(sum(vals) / len(vals), 1) if vals else None

    # Newest days first: the chat bounds long lists, and recent days matter most.
    daily = []
    d = end
    while d >= start:
        night = health.sleep_for_day(d)
        daily.append(
            {
                "day": d.isoformat(),
                "sleep_hours": round(night.total_hours, 2) if night else None,
                "deep_hours": round(night.deep_hours, 2)
                if night and night.deep_hours is not None
                else None,
                "rem_hours": round(night.rem_hours, 2)
                if night and night.rem_hours is not None
                else None,
                "resting_hr_bpm": mean(d, HealthRecordType.RESTING_HEART_RATE),
                "hrv_ms": mean(d, HealthRecordType.HRV),
                "steps": total(d, HealthRecordType.STEPS),
                "active_kcal": total(d, HealthRecordType.CALORIES),
                "exercise_minutes": total(d, "apple_exercise_time"),
            }
        )
        d -= timedelta(days=1)

    def workout(w) -> dict:
        local = (
            w.start_time.astimezone(ZoneInfo("Asia/Kolkata"))
            if w.start_time.tzinfo
            else w.start_time
        )
        return {
            "day": local.date().isoformat(),
            "start": local.strftime("%H:%M"),
            "type": w.label,
            "minutes": round(w.minutes, 1),
            "active_kcal": round(w.energy_kcal) if w.energy_kcal is not None else None,
            "avg_hr_bpm": round(w.avg_hr) if w.avg_hr is not None else None,
        }

    in_window = [workout(w) for w in reversed(health.workout_sessions(start, end))]
    latest = health.latest_workout(end)
    last = None
    if latest is not None:
        day = workout_day(latest)
        last = next(
            (workout(w) for w in reversed(health.workout_sessions(day, day))), None
        )
    last_received = health.last_received_at()
    export_seen = health.workout_export_seen_at()
    return {
        "window": _win(start, end, days),
        "freshness": {
            "health_data_last_arrived": last_received.isoformat()
            if last_received
            else None,
            "workouts_sync": (
                f"active since {export_seen.date().isoformat()}"
                if export_seen
                else "not set up; workouts after mid-June 2026 are unknown, not zero"
            ),
            "note": "Data arrives when the iPhone syncs; today and last night may be incomplete.",
        },
        "workouts": in_window,
        "by_month": _workouts_by_month(in_window),
        "window_totals": _workout_totals(in_window),
        "last_workout": last,
        "daily": daily,
    }


def _workout_totals(workouts: list[dict]) -> dict:
    kcal = [w["active_kcal"] for w in workouts if w["active_kcal"] is not None]
    return {
        "workouts": len(workouts),
        "minutes": round(sum(w["minutes"] for w in workouts), 1),
        "active_kcal": round(sum(kcal)) if kcal else None,
        "workout_days": len({w["day"] for w in workouts}),
    }


def _workouts_by_month(workouts: list[dict]) -> dict:
    """Per-calendar-month totals, so "how many in July" never needs hand counting."""
    months: dict[str, list[dict]] = {}
    for w in workouts:
        months.setdefault(w["day"][:7], []).append(w)
    return {month: _workout_totals(items) for month, items in sorted(months.items())}


def _window(days: int) -> tuple[date, date]:
    end = datetime.now(ZoneInfo("Asia/Kolkata")).date()
    return end - timedelta(days=days - 1), end


def _win(start: date, end: date, days: int) -> dict:
    return {"start": start.isoformat(), "end": end.isoformat(), "days": days}
