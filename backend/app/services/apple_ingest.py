"""Parse the JSON that the iOS app *Health Auto Export* POSTs to the REST API.

This is the live counterpart to ``scripts/import_apple_health.py``: instead of a
one-time XML file, the iPhone pushes recent Apple Health data (including Apple
Watch sleep) on a schedule. We map it onto the same ``HealthRecord`` schema and
ingest by ``source_id``, where a re-sent reading replaces the stored one — so Health Connect being unable to carry
Apple Watch sleep no longer matters.

Payload shape (https://github.com/Lybron/health-auto-export/wiki/API-Export---JSON-Format):

    {"data": {"metrics": [
        {"name": "heart_rate", "units": "bpm",
         "data": [{"date": "2026-06-18 07:00:00 +0530", "Min": 50, "Avg": 62, "Max": 80}]},
        {"name": "sleep_analysis", "units": "hr",
         "data": [{"date": "...", "sleepStart": "...", "sleepEnd": "...",
                   "totalSleep": 7.4, "deep": 1.1, "rem": 1.6, "core": 4.7,
                   "asleep": 7.4, "inBed": 8.0}]},
        ...
    ], "workouts": [...]}}
"""
from __future__ import annotations

import hashlib
import re
from datetime import datetime
from typing import Any

from app.schemas.health import HealthRecordIn

# Health Auto Export metric name -> the canonical type names scoring/analytics
# use. Anything not listed is stored under its own (already snake_case) name so
# no signal is dropped — consistent with the free-form `type` column.
_CANONICAL: dict[str, str] = {
    "heart_rate": "heart_rate",
    "resting_heart_rate": "resting_heart_rate",
    "heart_rate_variability": "hrv",
    "blood_oxygen_saturation": "blood_oxygen",
    "respiratory_rate": "respiratory_rate",
    "step_count": "steps",
    "active_energy": "calories",  # analytics reads active energy from CALORIES
    "walking_running_distance": "walking_distance",
    "flights_climbed": "floors_climbed",
    "weight_body_mass": "weight",
    "vo2_max": "vo2_max",
    "apple_sleeping_wrist_temperature": "basal_body_temperature",
}

_DATE_FORMATS = ("%Y-%m-%d %H:%M:%S %z", "%Y-%m-%d %H:%M:%S")


def _sid(*parts: object) -> str:
    raw = "|".join(str(p) for p in parts)
    return "hae-" + hashlib.md5(raw.encode()).hexdigest()


def _parse_date(s: Any) -> datetime | None:
    if not isinstance(s, str):
        return None
    for fmt in _DATE_FORMATS:
        try:
            return datetime.strptime(s, fmt)
        except ValueError:
            continue
    return None


def _to_float(v: Any) -> float | None:
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def _value_and_unit(rec_type: str, value: float, units: str) -> tuple[float, str]:
    """Normalize to the canonical unit LifeOS stores for well-known types."""
    u = (units or "").lower()
    if rec_type == "blood_oxygen":
        return (value * 100.0 if value <= 1.0 else value), "%"
    if rec_type == "walking_distance":
        if u == "km":
            return value * 1000.0, "m"
        if u == "mi":
            return value * 1609.344, "m"
        return value, "m"
    if rec_type == "weight":
        return (value * 0.45359237 if u == "lb" else value), "kg"
    if rec_type == "basal_body_temperature":
        return ((value - 32.0) * 5.0 / 9.0 if u in ("degf", "f") else value), "C"
    return value, (units or "")[:32]


def _sleep_records(p: dict[str, Any], units: str) -> list[HealthRecordIn]:
    start = _parse_date(p.get("sleepStart") or p.get("inBedStart") or p.get("date"))
    end = _parse_date(p.get("sleepEnd") or p.get("inBedEnd") or p.get("date"))
    if start is None or end is None or end <= start:
        return []
    # Aggregated sleep is reported in hours ("hr"); guard the rare minute case.
    scale = 1.0 / 60.0 if (units or "").lower().startswith("min") else 1.0
    key = p.get("sleepStart") or p.get("date")

    total = _to_float(p.get("totalSleep"))
    if total is None:
        total = _to_float(p.get("asleep"))
    # Skip fragments (<30min) and implausible spans (>16h in-bed artifacts).
    if total is None or not (0.5 <= total * scale <= 16.0):
        return []

    awake = _to_float(p.get("awake"))
    if awake is None:
        inbed = _to_float(p.get("inBed"))
        asleep = _to_float(p.get("asleep")) or total
        if inbed is not None and asleep is not None and inbed > asleep:
            awake = inbed - asleep

    out: list[HealthRecordIn] = []

    def add(rec_type: str, hours: float | None) -> None:
        if hours is None or hours <= 0:
            return
        out.append(HealthRecordIn(
            source_id=_sid(rec_type, key), type=rec_type, value=hours * scale,
            unit="h", start_time=start, end_time=end,
        ))

    add("sleep_duration", total)
    add("sleep_deep", _to_float(p.get("deep")))
    add("sleep_rem", _to_float(p.get("rem")))
    add("sleep_light", _to_float(p.get("core")))
    add("sleep_awake", awake)
    return out


def workout_label(raw: str | None) -> str:
    """A readable activity name from either export.

    Health Auto Export sends "Traditional Strength Training"; the Apple Health
    XML sends "HKWorkoutActivityTypeTraditionalStrengthTraining".
    """
    name = (raw or "").strip().removeprefix("HKWorkoutActivityType")
    if name and " " not in name:
        name = re.sub(r"(?<=[a-z])(?=[A-Z])", " ", name)
    return (name or "Workout")[:64]


def _quantity(value: Any) -> tuple[float | None, str]:
    """HAE quantities arrive as {"qty": .., "units": ..}; tolerate a bare number."""
    if isinstance(value, dict):
        return _to_float(value.get("qty")), str(value.get("units") or "")
    return _to_float(value), ""


def _workout_avg_hr(w: dict[str, Any]) -> float | None:
    hr, _ = _quantity(w.get("avgHeartRate"))
    if hr is None and isinstance(w.get("heartRate"), dict):
        hr, _ = _quantity(w["heartRate"].get("avg"))
    return hr


def _workout_records(w: dict[str, Any]) -> list[HealthRecordIn]:
    """A workout plus its active energy and average heart rate, when sent.

    The extra readings share the workout's times and label so the model can
    judge a session by its duration and effort, not merely that it exists.
    """
    rec = _workout_record(w)
    if rec is None:
        return []
    out = [rec]
    energy, units = _quantity(w.get("activeEnergyBurned"))
    if energy is not None and energy >= 0:
        kcal = energy / 4.184 if units.lower() == "kj" else energy
        out.append(rec.model_copy(update={
            "source_id": _sid("workout_energy", w.get("start") or str(rec.start_time), rec.label),
            "type": "workout_energy", "value": round(kcal, 1), "unit": "kcal",
        }))
    hr = _workout_avg_hr(w)
    if hr is not None and hr > 0:
        out.append(rec.model_copy(update={
            "source_id": _sid("workout_avg_hr", w.get("start") or str(rec.start_time), rec.label),
            "type": "workout_avg_hr", "value": round(hr, 1), "unit": "bpm",
        }))
    return out


def _workout_record(w: dict[str, Any]) -> HealthRecordIn | None:
    start = _parse_date(w.get("start") or w.get("startDate"))
    end = _parse_date(w.get("end") or w.get("endDate"))
    if start is None or end is None or end <= start:
        return None
    # The watch's own duration (seconds) excludes pauses; end - start does not.
    duration = _to_float(w.get("duration"))
    minutes = duration / 60.0 if duration and duration > 0 else (end - start).total_seconds() / 60.0
    name = w.get("name") or w.get("workoutActivityType") or "workout"
    return HealthRecordIn(
        source_id=_sid("workout", w.get("start") or str(start), name),
        type="workout", value=minutes, unit="min", start_time=start, end_time=end,
        label=workout_label(name),
    )


def parse_export(payload: dict[str, Any]) -> list[HealthRecordIn]:
    """Flatten a Health Auto Export payload into idempotent HealthRecordIn rows."""
    data = payload.get("data") or {}
    out: list[HealthRecordIn] = []

    for metric in data.get("metrics") or []:
        if not isinstance(metric, dict):
            continue
        name = metric.get("name") or ""
        units = metric.get("units") or ""
        points = metric.get("data") or []
        if name == "sleep_analysis":
            for p in points:
                if isinstance(p, dict):
                    out += _sleep_records(p, units)
            continue
        rec_type = _CANONICAL.get(name, name)[:64]
        for p in points:
            if not isinstance(p, dict):
                continue
            dt = _parse_date(p.get("date"))
            if dt is None:
                continue
            # Quantity points use "qty"; heart-rate-style points use Min/Avg/Max.
            raw = p.get("qty")
            if raw is None:
                raw = p.get("Avg")
            value = _to_float(raw)
            if value is None:
                continue
            value, unit = _value_and_unit(rec_type, value, units)
            # Keyed by metric + timestamp only: HAE re-sends a day's aggregate
            # with a corrected value, and that must replace the reading, not
            # sit beside it (a value-keyed id stored both and summed them).
            out.append(HealthRecordIn(
                source_id=_sid(rec_type, p.get("date")),
                type=rec_type, value=value, unit=unit, start_time=dt, end_time=dt,
            ))

    for w in data.get("workouts") or []:
        if isinstance(w, dict):
            out += _workout_records(w)

    return out
