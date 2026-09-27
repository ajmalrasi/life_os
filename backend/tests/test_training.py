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
    # Token sessions still count; the model is shown how small they were.
    assert len(s.recent) == 4 and s.days_since_last == 1
    assert s.weekly_counts == [0] * 7 + [1, 0, 0, 0, 0, 3]
    assert s.weekly_minutes[-1] == 1.5
    assert [(w.minutes, w.energy_kcal) for w in s.recent[1:]] == [(0.5, 2.0)] * 3


def test_insights_and_weekly_recap_see_workouts_with_details(client):
    today = date.today()
    db = SessionLocal()
    repo = HealthRepository(db)
    repo.bulk_insert(_rows("hae-a", today - timedelta(days=58), 50, "Traditional Strength Training", 367))
    repo.mark_workout_export()

    fact = next(f for f in life_evidence._life_signal_facts(db, today) if f.id == "health:workouts")
    assert "Workouts in the last 13 weeks (1):" in fact.statement
    assert "Days since the most recent workout: 58." in fact.statement
    assert "(58 days ago)" in fact.statement
    assert "Traditional Strength Training, 50 min, 367 kcal active" in fact.statement

    recap = next(f for f in weekly_recap.compute_weekly_facts(db, today) if f.id == "workouts")
    assert recap.statement.startswith("Workouts this week: 0 (last week: 0)")
    assert "367 kcal active" in recap.statement
    db.close()


def test_one_session_in_a_quiet_stretch_shows_the_gaps_on_both_sides(client):
    """The Sep 2026 case: a June routine, Jul 29 lifting, one 19-minute walk 21 days ago."""
    today = date.today()
    db = SessionLocal()
    repo = HealthRepository(db)
    repo.bulk_insert(
        [r for d in (86, 84, 82) for r in _rows(f"hae-june{d}", today - timedelta(days=d), 60, "Traditional Strength Training", 400)]
        + _rows("hae-lift", today - timedelta(days=58), 50, "Traditional Strength Training", 367)
        + _rows("hae-walk", today - timedelta(days=21), 19, "Outdoor Walk")
    )
    from app.services.training import workout_statement
    statement = workout_statement(workout_summary(repo, today))
    db.close()
    assert "Workouts in the last 13 weeks (5):" in statement
    assert "(21 days ago)" in statement and "Outdoor Walk, 19 min" in statement
    assert "(58 days ago)" in statement and "Traditional Strength Training, 50 min" in statement
    # The trend reads directly: a routine, one session, a long gap, one walk, nothing since.
    assert "as sessions/minutes: 2/120, 1/60, 0/0, 0/0, 1/50, 0/0, 0/0, 0/0, 0/0, 1/19, 0/0, 0/0, 0/0." in statement
    assert "Days since the most recent workout: 21." in statement
    def md(n):
        x = date.today() - timedelta(days=n)
        return f"{x:%b} {x.day}"
    # Both sides of the lone walk, stated plainly.
    assert f"all ended since (history, not the current state): {md(82)} to {md(58)} (24 days); {md(58)} to {md(21)} (37 days)." in statement
    assert f"Current gap, still open: {md(21)} to today (21 days so far)." in statement
    assert statement.startswith("Right now: the most recent workout was 21 days ago, with 0 sessions")


def test_ended_gap_is_not_the_current_state(client):
    """A long gap that ended must not read as a lapse (it was called 'lapsed 37 days')."""
    from app.services.training import workout_statement
    today = date.today()
    db = SessionLocal()
    repo = HealthRepository(db)
    repo.bulk_insert(
        _rows("hae-old", today - timedelta(days=40), 50, "Traditional Strength Training", 367)
        + _rows("hae-new", today - timedelta(days=1), 35, "Traditional Strength Training", 254)
    )
    statement = workout_statement(workout_summary(repo, today))
    db.close()
    assert statement.startswith("Right now: the most recent workout was 1 day ago, with 1 session in")
    assert "(39 days)." in statement and "all ended since" in statement
    assert "Current gap" not in statement
