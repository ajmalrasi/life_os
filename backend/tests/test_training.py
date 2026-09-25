"""Every workout counts; the model, not a threshold, judges whether it was real exercise."""

from __future__ import annotations

from datetime import date, datetime, timedelta

from app.database import SessionLocal
from app.repositories.analytics_repo import ScoreRepository
from app.repositories.health_repo import HealthRepository
from app.schemas.health import HealthRecordIn
from app.services import life_evidence, weekly_recap
from app.services.analytics import AnalyticsService
from app.services.training import workout_summary


def _rows(sid: str, d: date, minutes: float, label: str, kcal: float | None = None) -> list[HealthRecordIn]:
    start = datetime.combine(d, datetime.min.time()).replace(hour=19)
    end = start + timedelta(minutes=minutes)
    rows = [HealthRecordIn(source_id=sid, type="workout", value=minutes, unit="min",
                           start_time=start, end_time=end, label=label)]
    if kcal is not None:
        rows.append(HealthRecordIn(source_id=sid + "-e", type="workout_energy", value=kcal, unit="kcal",
                                   start_time=start, end_time=end, label=label))
    return rows


def test_a_logged_walk_counts_toward_the_fitness_score(client):
    day = date.today() - timedelta(days=2)
    db = SessionLocal()
    HealthRepository(db).bulk_insert(_rows("hae-walk", day, 45, "Outdoor Walk"))
    AnalyticsService(db).recompute_day(day)
    db.commit()
    assert ScoreRepository(db).get(day).fitness_score == 55.0
    db.close()


def test_summary_lists_every_session_with_its_effort(client):
    today = date.today()
    db = SessionLocal()
    repo = HealthRepository(db)
    repo.bulk_insert(
        _rows("hae-a", today - timedelta(days=40), 50, "Traditional Strength Training", 367)
        + [r for d in range(1, 4) for r in _rows(f"hae-t{d}", today - timedelta(days=d), 0.5, "Functional Strength Training", 2)]
    )
    s = workout_summary(repo, today)
    db.close()
    # Three token sessions still count as sessions; the model is shown how small they were.
    assert len(s.recent) == 3 and s.sessions_prev_28 == 1 and s.days_since_last == 1
    assert [(w.minutes, w.energy_kcal) for w in s.recent] == [(0.5, 2.0)] * 3


def test_insights_and_weekly_recap_see_workouts_with_details(client):
    today = date.today()
    db = SessionLocal()
    repo = HealthRepository(db)
    repo.bulk_insert(_rows("hae-a", today - timedelta(days=58), 50, "Traditional Strength Training", 367))
    repo.mark_workout_export()

    fact = next(f for f in life_evidence._life_signal_facts(db, today) if f.id == "health:workouts")
    assert "No workouts in the last 28 days." in fact.statement
    assert "(58 days ago)" in fact.statement
    assert "Traditional Strength Training, 50 min, 367 kcal active" in fact.statement

    recap = next(f for f in weekly_recap.compute_weekly_facts(db, today) if f.id == "workouts")
    assert recap.statement.startswith("Workouts this week: 0 (last week: 0)")
    assert "367 kcal active" in recap.statement
    db.close()


def test_a_recent_walk_does_not_hide_the_last_real_session(client):
    today = date.today()
    db = SessionLocal()
    repo = HealthRepository(db)
    repo.bulk_insert(
        _rows("hae-lift", today - timedelta(days=58), 50, "Traditional Strength Training", 367)
        + _rows("hae-walk", today - timedelta(days=21), 19, "Outdoor Walk")
    )
    from app.services.training import workout_statement
    statement = workout_statement(workout_summary(repo, today))
    db.close()
    assert "Workouts in the last 28 days (1):" in statement and "Outdoor Walk, 19 min" in statement
    assert "Latest workout before that window:" in statement
    assert "(58 days ago)" in statement and "(21 days ago)" in statement
    assert "Traditional Strength Training, 50 min, 367 kcal active" in statement
