"""Spending as a first-class analytics signal.

Covers the daily-spend aggregation (which debits count) and that the correlation
engine surfaces a money↔behavior pattern once spend is wired in.
"""

from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal

from app.models.mood import MoodCheckin
from app.models.transaction import Transaction
from app.repositories.expense_repo import ExpenseRepository
from app.services.analytics import AnalyticsService


def _txn(day: date, amount: str, direction: str = "debit", category: str | None = None, mid: str = "m") -> Transaction:
    return Transaction(
        txn_date=day,
        amount=Decimal(amount),
        direction=direction,
        category=category,
        source="upi",
        gmail_message_id=mid,
        dedup_key=f"{mid}:{day}:{amount}:{category}",
    )


def test_daily_spend_sums_real_debits_only(client):
    from app.database import SessionLocal

    day = date(2026, 7, 1)
    with SessionLocal() as db:
        db.add_all([
            _txn(day, "100.00", mid="a"),                       # real spend
            _txn(day, "50.00", category=None, mid="b"),         # real spend (uncategorized)
            _txn(day, "9999.00", category="transfer", mid="c"),  # self-transfer, excluded
            _txn(day, "42.00", category="reversed_debit", mid="d"),  # reversal, excluded
            _txn(day, "500.00", direction="credit", mid="e"),   # income, not a debit
        ])
        db.commit()

        spend = ExpenseRepository(db).daily_spend(day, day)

    assert spend == {day: 150.0}


def test_daily_spend_buckets_by_day_and_skips_empty(client):
    from app.database import SessionLocal

    d1, d2 = date(2026, 7, 1), date(2026, 7, 3)
    with SessionLocal() as db:
        db.add_all([
            _txn(d1, "200.00", mid="a"),
            _txn(d1, "300.00", mid="b"),
            _txn(d2, "75.00", mid="c"),
        ])
        db.commit()
        spend = ExpenseRepository(db).daily_spend(date(2026, 7, 1), date(2026, 7, 5))

    assert spend == {d1: 500.0, d2: 75.0}  # 07-02/04/05 absent (no debits)


def test_discover_surfaces_stress_to_spend_pattern(client, monkeypatch):
    """Stress and spend rising together should be discovered as an insight."""
    from app.database import SessionLocal
    import app.services.analytics as analytics_mod

    # Keep the LLM narrator off the wire — narration is a best-effort nicety.
    monkeypatch.setattr(analytics_mod.insight_narrator, "narrate", lambda facts, client=None: {})

    start = date(2026, 6, 20)
    days = [start + timedelta(days=i) for i in range(10)]

    with SessionLocal() as db:
        for i, day in enumerate(days):
            stress = 1 + (i % 5)            # 1..5 cycling
            db.add(MoodCheckin(day=day, mood=3, energy=3, focus=3, stress=stress))
            db.add(_txn(day, f"{stress * 500}.00", mid=f"s{i}"))  # spend tracks stress
        db.commit()

        insights = AnalyticsService(db).discover(days[0], days[-1])
        rows = ExpenseRepository(db)  # noqa: F841 - clarity
        from app.repositories.analytics_repo import InsightRepository
        found = InsightRepository(db).all()

    assert insights > 0
    spend_insights = [r for r in found if "daily_spend" in (r.driver, r.outcome)]
    assert spend_insights, "expected a spending correlation to be discovered"
    stress_spend = [
        r for r in spend_insights
        if {r.driver, r.outcome} == {"stress", "daily_spend"}
    ]
    assert stress_spend and abs(stress_spend[0].correlation) >= 0.5
