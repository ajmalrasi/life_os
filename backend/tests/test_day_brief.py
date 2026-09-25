"""Health-data correctness behind the Today page, and the model-written brief."""

from __future__ import annotations

from datetime import date, datetime, timedelta

from app.database import SessionLocal
from app.repositories.analytics_repo import DailyBriefRepository, StateRepository
from app.repositories.health_repo import HealthRepository
from app.schemas.health import HealthRecordIn
from app.services import day_brief
from app.services.apple_ingest import parse_export
from app.services.insight_synthesizer import EvidenceFact


def _at(d: date, hour: int, minute: int = 0) -> datetime:
    return datetime.combine(d, datetime.min.time()).replace(hour=hour, minute=minute)


def _sleep(sid: str, start: datetime, end: datetime, hours: float, **stages: float) -> list[HealthRecordIn]:
    rows = [HealthRecordIn(source_id=f"{sid}-d", type="sleep_duration", value=hours, unit="h", start_time=start, end_time=end)]
    for name, value in stages.items():
        rows.append(HealthRecordIn(source_id=f"{sid}-{name}", type=f"sleep_{name}", value=value, unit="h", start_time=start, end_time=end))
    return rows


def test_night_that_crosses_midnight_belongs_to_the_wake_day(client):
    today = date.today()
    db = SessionLocal()
    repo = HealthRepository(db)
    repo.bulk_insert(_sleep("hae-a", _at(today - timedelta(days=1), 23, 30), _at(today, 7), 7.0, deep=1.2))
    assert repo.sleep_for_day(today - timedelta(days=1)) is None
    night = repo.sleep_for_day(today)
    assert night.total_hours == 7.0 and night.deep_hours == 1.2
    db.close()


def test_overlapping_versions_of_a_night_count_once_but_a_nap_adds(client):
    """The Sep 22 case: a revised night and a nap were summed to 13.9 h."""
    today = date.today()
    db = SessionLocal()
    repo = HealthRepository(db)
    repo.bulk_insert(
        _sleep("hae-early", _at(today, 4, 21), _at(today, 10, 14), 5.1, awake=0.1)
        + _sleep("hae-revised", _at(today, 5, 51), _at(today, 11, 0), 6.2, awake=0.05)
        + _sleep("apple-import", _at(today, 4, 30), _at(today, 10, 0), 5.0)
        + _sleep("hae-nap", _at(today, 15, 30), _at(today, 16, 30), 1.0)
    )
    night = repo.sleep_for_day(today)
    assert round(night.total_hours, 2) == 7.2  # longest night version 6.2 + nap 1.0
    assert night.awake_hours == 0.05
    db.close()


def test_resent_daily_value_replaces_instead_of_adding(client):
    today = date.today()
    stamp = f"{today.isoformat()} 00:00:00 +0530"

    def push(steps: float, hrv: float):
        return {"data": {"metrics": [
            {"name": "step_count", "units": "count", "data": [{"date": stamp, "qty": steps}]},
            {"name": "heart_rate_variability", "units": "ms", "data": [{"date": stamp, "qty": hrv}]},
        ]}}

    first = client.post("/health/auto-export", json=push(1000, 40)).json()
    second = client.post("/health/auto-export", json=push(2500, 52)).json()
    again = client.post("/health/auto-export", json=push(2500, 52)).json()
    assert first["inserted"] == 2
    assert second["inserted"] == 0 and second["updated"] == 2
    assert again["skipped"] == 2

    db = SessionLocal()
    repo = HealthRepository(db)
    assert repo.daily_values(today, "steps") == [2500]
    assert repo.daily_values(today, "hrv") == [52]
    db.close()


def test_legacy_value_keyed_duplicates_collapse_to_newest(client):
    """Rows stored before the key fix (one per revised value) still count once."""
    today = date.today()
    db = SessionLocal()
    repo = HealthRepository(db)
    stamp = _at(today, 0)
    for i, v in enumerate((50.3, 43.0, 51.3)):
        repo.bulk_insert([HealthRecordIn(source_id=f"hae-old-{i}", type="hrv", value=v, unit="ms", start_time=stamp, end_time=stamp)])
    assert repo.daily_values(today, "hrv") == [51.3]
    assert round(repo.baseline_mean(today, today + timedelta(days=1), "hrv"), 1) == 51.3
    db.close()


def test_sleep_revision_with_same_start_updates_in_place():
    base = {"sleepStart": "2026-09-25 05:00:00 +0530", "date": "2026-09-25 00:00:00 +0530"}
    early = parse_export({"data": {"metrics": [{"name": "sleep_analysis", "units": "hr", "data": [
        {**base, "sleepEnd": "2026-09-25 07:00:00 +0530", "totalSleep": 2.0}]}]}})
    later = parse_export({"data": {"metrics": [{"name": "sleep_analysis", "units": "hr", "data": [
        {**base, "sleepEnd": "2026-09-25 11:00:00 +0530", "totalSleep": 5.8}]}]}})
    assert early[0].source_id == later[0].source_id


FACTS = [
    EvidenceFact("now", "Current local time is 16:20 on Friday, afternoon."),
    EvidenceFact("sleep_last_night", "Last night: slept 3.7 h, 05:59 to 11:03; deep 0.1 h, awake 1.4 h."),
    EvidenceFact("hrv", "HRV 43 ms (yesterday); 30-day usual 45 ms."),
]


def _reply(**overrides):
    reply = {
        "state": "recovery_mode",
        "confidence": "medium",
        "headline": "Short, broken sleep",
        "summary": "You slept 3.7 h with 1.4 h awake; HRV is 43 ms against a usual 45 ms.",
        "suggestion": "Keep today light and get to bed early.",
        "cites": ["sleep_last_night", "hrv"],
    }
    reply.update(overrides)
    return reply


def test_brief_accepts_grounded_reply():
    assert day_brief.validate_brief(_reply(), FACTS)["state"] == "recovery_mode"


def test_brief_rejects_invented_numbers_and_fake_citations():
    assert day_brief.validate_brief(_reply(summary="You slept 4 h."), FACTS) is None
    assert day_brief.validate_brief(_reply(cites=["mood"]), FACTS) is None
    assert day_brief.validate_brief(_reply(cites=[]), FACTS) is None
    assert day_brief.validate_brief(_reply(state="great_day"), FACTS) is None


def test_facts_hash_ignores_clock_minutes_and_arrival_time():
    later = [EvidenceFact("now", "Current local time is 16:45 on Friday, afternoon."), *FACTS[1:],
             EvidenceFact("health_freshness", "Apple Health data last arrived at 16:40 today.")]
    assert day_brief.facts_hash(FACTS) == day_brief.facts_hash(later)
    changed = [*FACTS[:2], EvidenceFact("hrv", "HRV 50 ms (today); 30-day usual 45 ms.")]
    assert day_brief.facts_hash(FACTS) != day_brief.facts_hash(changed)


class _Model:
    def __init__(self, reply):
        self.reply = reply
        self.timeout = 90.0
        self.calls = 0

    def chat_json(self, messages, schema, name, **kwargs):
        self.calls += 1
        return self.reply


def test_generate_brief_stores_state_and_skips_when_facts_unchanged(client):
    today = date.today()
    db = SessionLocal()
    HealthRepository(db).bulk_insert(_sleep("hae-n", _at(today, 5), _at(today, 9), 4.0))
    facts = day_brief.compute_day_facts(db, today)
    assert "slept 4.0 h" in next(f.statement for f in facts if f.id == "sleep_last_night")

    model = _Model(_reply(summary="You slept 4.0 h last night.", cites=["sleep_last_night"]))
    brief = day_brief.generate_brief(db, today, client=model)
    assert brief.state == "recovery_mode"
    assert StateRepository(db).get(today).rationale == "You slept 4.0 h last night."

    day_brief.generate_brief(db, today, client=model)
    assert model.calls == 1
    db.close()


def test_rejected_reply_keeps_previous_brief(client):
    today = date.today()
    db = SessionLocal()
    good = day_brief.generate_brief(db, today, client=_Model(_reply(summary="No sleep yet.", cites=["sleep_last_night"])))
    HealthRepository(db).bulk_insert(_sleep("hae-m", _at(today, 5), _at(today, 9), 4.0))
    kept = day_brief.generate_brief(db, today, client=_Model(_reply(summary="You slept 9 h.")))
    assert kept.id == good.id and kept.summary == "No sleep yet."
    assert DailyBriefRepository(db).get(today).summary == "No sleep yet."
    db.close()


def test_today_endpoint_reports_brief_status(client):
    body = client.get("/analytics/today").json()
    assert body["brief"] is None
    assert body["brief_status"] in {"refreshing", "unavailable"}


def test_facts_state_the_direction_of_a_comparison():
    assert day_brief._versus(55, 64, "lower") == "below your usual, which is better"
    assert day_brief._versus(31, 40, "higher") == "below your usual, which is worse"
    assert day_brief._versus(41, 40, "higher") == "about your usual"


def _workout(name: str, start: datetime, minutes: int) -> dict:
    fmt = "%Y-%m-%d %H:%M:%S +0530"
    return {"name": name, "start": start.strftime(fmt), "end": (start + timedelta(minutes=minutes)).strftime(fmt)}


def _workout_fact(day: date) -> str:
    db = SessionLocal()
    fact = next(f for f in day_brief.compute_day_facts(db, day) if f.id == "workouts")
    db.close()
    return fact.statement


def test_workout_type_is_kept_and_both_strength_sessions_are_told_apart(client):
    today = date.today()
    day = today - timedelta(days=3)
    payload = {"data": {"workouts": [
        _workout("Traditional Strength Training", _at(day, 19), 40),
        _workout("Functional Strength Training", _at(day, 19, 45), 20),
    ]}}
    assert client.post("/health/auto-export", json=payload).json()["inserted"] == 2

    db = SessionLocal()
    rows = HealthRepository(db).workouts_between(day, day)
    assert [(r.label, round(r.value)) for r in rows] == [
        ("Traditional Strength Training", 40),
        ("Functional Strength Training", 20),
    ]
    db.close()

    fact = _workout_fact(today)
    assert "Workouts in the last 28 days: 2, 60 min in total" in fact
    assert "Traditional Strength Training, Functional Strength Training" in fact
    assert "Most recent recorded workout: Functional Strength Training, 20 min" in fact
    assert "3 days ago" in fact
    assert "unknown" not in fact


def test_workouts_are_unknown_until_the_phone_sends_a_workouts_export(client):
    today = date.today()
    db = SessionLocal()
    HealthRepository(db).bulk_insert([HealthRecordIn(
        source_id="apple-w1", type="workout", value=52, unit="min",
        start_time=_at(today - timedelta(days=90), 20), end_time=_at(today - timedelta(days=90), 21),
        label="Traditional Strength Training",
    )])
    db.close()
    fact = _workout_fact(today)
    assert "90 days ago" in fact and "unknown, not zero" in fact

    # An empty workouts export means "none recorded", not "not sent".
    client.post("/health/auto-export", json={"data": {"workouts": []}})
    fact = _workout_fact(today)
    assert "unknown" not in fact
    assert "No workouts in the last 28 days." in fact

    # A health-metrics export alone never claims anything about workouts.
    db = SessionLocal()
    assert HealthRepository(db).workout_export_seen_at() is not None
    db.close()


def test_resending_a_workout_fills_in_a_missing_type(client):
    day = date.today() - timedelta(days=1)
    start = _at(day, 7)
    record = parse_export({"data": {"workouts": [_workout("Functional Strength Training", start, 30)]}})[0]
    db = SessionLocal()
    repo = HealthRepository(db)
    repo.upsert_records([record.model_copy(update={"label": None})])
    assert repo.workouts_between(day, day)[0].label is None
    assert repo.upsert_records([record]) == (0, 1)
    assert repo.workouts_between(day, day)[0].label == "Functional Strength Training"
    db.close()


def test_apple_xml_activity_names_become_readable():
    from app.services.apple_ingest import workout_label

    assert workout_label("HKWorkoutActivityTypeTraditionalStrengthTraining") == "Traditional Strength Training"
    assert workout_label("Functional Strength Training") == "Functional Strength Training"
    assert workout_label(None) == "Workout"
