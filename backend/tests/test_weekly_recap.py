"""Weekly recap: deterministic facts, best-effort narration, and the route."""

from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal

from app.database import SessionLocal
from app.repositories.analytics_repo import ScoreRepository
from app.repositories.expense_repo import ExpenseRepository
from app.services.expense_parsers.base import ParsedTxn
from app.services.weekly_recap import compute_weekly_facts, week_bounds


def _seed_spend(amount: str, when: date):
    with SessionLocal() as db:
        ExpenseRepository(db).upsert(
            ParsedTxn(
                txn_date=when,
                amount=Decimal(amount),
                direction="debit",
                source="upi",
                counterparty="SOME MERCHANT",
            ),
            message_id=f"m-{amount}-{when}",
            dedup_key=f"k-{amount}-{when}",
        )
        db.commit()


def _seed_sleep_score(day: date, score: float):
    with SessionLocal() as db:
        ScoreRepository(db).upsert(day, sleep_score=score)


def test_week_bounds_ends_yesterday_not_today():
    today = date(2026, 9, 24)  # a Thursday
    prev_start, prev_end, start, end = week_bounds(today)
    assert end == date(2026, 9, 23)
    assert start == date(2026, 9, 17)
    assert prev_end == date(2026, 9, 16)
    assert prev_start == date(2026, 9, 10)


def test_compute_weekly_facts_empty_when_no_data(client):
    facts = compute_weekly_facts(SessionLocal(), today=date.today())
    assert facts == []


def test_compute_weekly_facts_includes_spend_comparison(client):
    today = date.today()
    _, _, start, end = week_bounds(today)
    _seed_spend("1000.00", start)
    facts = compute_weekly_facts(SessionLocal(), today=today)
    spend_fact = next(f for f in facts if f.id == "spend")
    assert "1,000" in spend_fact.statement


def test_compute_weekly_facts_includes_sleep_comparison(client):
    today = date.today()
    _, _, start, end = week_bounds(today)
    _seed_sleep_score(start, 72.0)
    facts = compute_weekly_facts(SessionLocal(), today=today)
    sleep_fact = next(f for f in facts if f.id == "sleep")
    assert "72" in sleep_fact.statement


def test_weekly_recap_route_needs_data_first(client):
    resp = client.post("/analytics/weekly-recap")
    assert resp.status_code == 404


def test_weekly_recap_route_stores_and_serves_latest(client):
    today = date.today()
    _, _, start, end = week_bounds(today)
    _seed_spend("500.00", start)

    resp = client.post("/analytics/weekly-recap")
    assert resp.status_code == 200
    body = resp.json()
    assert body["week_start"] == start.isoformat()
    assert body["week_end"] == end.isoformat()
    assert "500" in body["prose"]

    fetched = client.get("/analytics/weekly-recap")
    assert fetched.status_code == 200
    assert fetched.json()["prose"] == body["prose"]

    # Re-running the same week overwrites, not duplicates.
    resp2 = client.post("/analytics/weekly-recap")
    assert resp2.status_code == 200
