"""Training workouts are judged apart from walks everywhere exercise is scored."""

from __future__ import annotations

from datetime import date, datetime, timedelta

from app.database import SessionLocal
from app.repositories.analytics_repo import ScoreRepository
from app.repositories.health_repo import HealthRepository
from app.schemas.health import HealthRecordIn
from app.services import life_evidence, weekly_recap
from app.services.analytics import AnalyticsService
from app.services.training import training_summary


def _workout(sid: str, d: date, minutes: float, label: str) -> HealthRecordIn:
    start = datetime.combine(d, datetime.min.time()).replace(hour=19)
    return HealthRecordIn(source_id=sid, type="workout", value=minutes, unit="min",
                          start_time=start, end_time=start + timedelta(minutes=minutes), label=label)


def test_a_walk_logged_as_a_workout_does_not_raise_the_fitness_score(client):
    day = date.today() - timedelta(days=2)
    db = SessionLocal()
    scores = ScoreRepository(db)
    HealthRepository(db).bulk_insert([_workout("hae-walk", day, 60, "Outdoor Walk")])
    AnalyticsService(db).recompute_day(day)
    db.commit()
    walk_only = scores.get(day).fitness_score
    HealthRepository(db).bulk_insert([_workout("hae-lift", day, 45, "Traditional Strength Training")])
    AnalyticsService(db).recompute_day(day)
    db.commit()
    with_lift = scores.get(day).fitness_score
    db.close()
    assert walk_only == 0.0
    assert with_lift == 55.0


def test_training_summary_counts_windows_and_the_gap(client):
    today = date.today()
    db = SessionLocal()
    repo = HealthRepository(db)
    repo.bulk_insert([
        _workout("hae-a", today - timedelta(days=58), 50, "Traditional Strength Training"),
        _workout("hae-b", today - timedelta(days=40), 30, "Functional Strength Training"),
        _workout("hae-c", today - timedelta(days=3), 20, "Outdoor Walk"),
    ])
    s = training_summary(repo, today)
    db.close()
    assert s.last_label == "Functional Strength Training" and s.days_since_last == 40
    assert (s.sessions_last_28, s.sessions_prev_28, s.sessions_last_7) == (0, 1, 0)


def test_insights_and_weekly_recap_see_the_training_gap(client):
    today = date.today()
    db = SessionLocal()
    repo = HealthRepository(db)
    repo.bulk_insert([_workout("hae-a", today - timedelta(days=58), 50, "Traditional Strength Training")])
    repo.mark_workout_export()

    fact = next(f for f in life_evidence._life_signal_facts(db, today) if f.id == "health:training")
    assert "Last training workout: Traditional Strength Training, 50 min" in fact.statement
    assert "58 days ago" in fact.statement
    assert "0 in the last 28 days" in fact.statement
    assert "unknown" not in fact.statement

    recap = next(f for f in weekly_recap.compute_weekly_facts(db, today) if f.id == "training")
    assert recap.statement.startswith("Training workouts this week: 0 (last week: 0)")
    assert "Traditional Strength Training" in recap.statement
    db.close()
