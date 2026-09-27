"""A day's score row created by a concurrent request must not fail the upsert."""

from __future__ import annotations

from datetime import date

from app.database import SessionLocal
from app.models.daily_score import DailyScore
from app.repositories.analytics_repo import ScoreRepository


def test_upsert_updates_a_row_created_concurrently(client):
    day = date(2026, 9, 27)
    with SessionLocal() as db:
        repo = ScoreRepository(db)
        real_get = repo.get
        calls = {"n": 0}

        def stale_get(d):
            # First lookup misses; meanwhile another request inserts the row.
            calls["n"] += 1
            if calls["n"] == 1:
                with SessionLocal() as other:
                    other.add(DailyScore(day=d, sleep_score=10.0))
                    other.commit()
                return None
            return real_get(d)

        repo.get = stale_get
        obj = repo.upsert(day, sleep_score=80.0)
        assert obj.sleep_score == 80.0

    with SessionLocal() as db:
        rows = db.query(DailyScore).filter(DailyScore.day == day).all()
        assert [r.sleep_score for r in rows] == [80.0]
