"""Parse the JSON that the iOS app *Health Auto Export* POSTs to the REST API.

This is the live counterpart to ``scripts/import_apple_health.py``: instead of a
one-time XML file, the iPhone pushes recent Apple Health data (including Apple
Watch sleep) on a schedule. We map it onto the same ``HealthRecord`` schema and
ingest idempotently by ``source_id`` — so Health Connect being unable to carry
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


def _workout_record(w: dict[str, Any]) -> HealthRecordIn | None:
    start = _parse_date(w.get("start") or w.get("startDate"))
    end = _parse_date(w.get("end") or w.get("endDate"))
    if start is None or end is None or end <= start:
        return None
    minutes = (end - start).total_seconds() / 60.0
    name = w.get("name") or w.get("workoutActivityType") or "workout"
    return HealthRecordIn(
        source_id=_sid("workout", w.get("start") or str(start), name),
        type="workout", value=minutes, unit="min", start_time=start, end_time=end,
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
            out.append(HealthRecordIn(
                source_id=_sid(rec_type, p.get("date"), value),
                type=rec_type, value=value, unit=unit, start_time=dt, end_time=dt,
            ))

    for w in data.get("workouts") or []:
        if isinstance(w, dict):
            rec = _workout_record(w)
            if rec is not None:
                out.append(rec)

    return out
