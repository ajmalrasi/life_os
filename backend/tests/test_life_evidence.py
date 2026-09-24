"""Evidence builder for mentor-style Insights (Stage 2 of mentor-insights.md)."""

from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal

import pytest

from app.database import SessionLocal
from app.models.budget import Budget
from app.models.money_profile import MoneyProfile
from app.models.trip import TripBackup
from app.models.transaction import Transaction
from app.repositories.expense_repo import ExpenseRepository
from app.services import life_evidence
from app.services.expense_parsers.base import ParsedTxn
from app.services.life_evidence import build_life_evidence


def _seed_txn(
    amount: str,
    when: date,
    counterparty: str = "SOME MERCHANT",
    *,
    override: str | None = None,
    category: str | None = None,
    direction: str = "debit",
    source: str = "upi",
):
    with SessionLocal() as db:
        repo = ExpenseRepository(db)
        obj = repo.upsert(
            ParsedTxn(
                txn_date=when,
                amount=Decimal(amount),
                direction=direction,
                source=source,
                counterparty=counterparty,
                category=category,
            ),
            message_id=f"m-{amount}-{when}-{counterparty}-{direction}",
            dedup_key=f"k-{amount}-{when}-{counterparty}-{direction}-{override}",
        )
        if obj is not None and override is not None:
            obj.spend_category_override = override
        db.commit()


def _set_income(amount: str | None, goal: int | None = None):
    with SessionLocal() as db:
        row = db.get(MoneyProfile, 1)
        if row is None:
            row = MoneyProfile(id=1)
            db.add(row)
        row.monthly_income = Decimal(amount) if amount is not None else None
        row.savings_goal_pct = goal
        db.commit()


def test_income_missing_when_no_profile(client):
    facts = build_life_evidence(SessionLocal(), today=date.today())
    assert any(f.id == "money:income_missing" for f in facts)
    assert not any(f.id == "money:spend_vs_income" for f in facts)


def test_spend_vs_income_percent(client):
    today = date.today()
    _set_income("100000")
    _seed_txn("10000.00", today, "SHOP", override="shopping")
    facts = build_life_evidence(SessionLocal(), today=today)
    fact = next(f for f in facts if f.id == "money:spend_vs_income")
    assert "10%" in fact.statement or "10 %" in fact.statement


def test_category_multiple_computed(client):
    today = date.today()
    bounds = life_evidence._month_bounds(today)
    _seed_txn("3000.00", today, "PETROL PUMP", override="fuel")
    # Baseline: 1000/month average across the 3-month baseline window.
    _seed_txn("1000.00", bounds["baseline_start"], "PETROL PUMP", override="fuel")
    _seed_txn("1000.00", bounds["baseline_start"] + timedelta(days=32), "PETROL PUMP", override="fuel")
    _seed_txn("1000.00", bounds["baseline_end"], "PETROL PUMP", override="fuel")
    facts = build_life_evidence(SessionLocal(), today=today)
    fact = next(f for f in facts if f.id == "cat:fuel")
    assert "3.0x the usual" in fact.statement


def test_excluded_categories_do_not_count_as_spend(client):
    today = date.today()
    _set_income("50000")
    _seed_txn("5000.00", today, "SELF TRANSFER", override=None, category="transfer")
    _seed_txn("2000.00", today, "FRIEND", override=None, category="lent_returned")
    _seed_txn("1000.00", today, "REVERSED", override=None, category="reversed_debit")
    facts = build_life_evidence(SessionLocal(), today=today)
    fact = next(f for f in facts if f.id == "money:mtd")
    assert "₹0" in fact.statement


def test_spend_category_override_wins(client):
    today = date.today()
    _seed_txn("2000.00", today, "ZOMATO ONLINE", override="entertainment")
    facts = build_life_evidence(SessionLocal(), today=today)
    fact = next(f for f in facts if f.id == "cat:entertainment")
    assert "₹2,000" in fact.statement


def test_comparison_only_emitted_above_ratio(client):
    today = date.today()
    _seed_txn("1000.00", today, "GROCERY STORE", override="groceries")
    _seed_txn("1400.00", today, "VEHICLE SHOP", override="vehicle")
    facts = build_life_evidence(SessionLocal(), today=today)
    assert not any(f.id.startswith("cmp:") for f in facts)

    _seed_txn("1000.00", today, "VEHICLE SHOP", override="vehicle")
    facts = build_life_evidence(SessionLocal(), today=today)
    assert any(f.id == "cmp:vehicle_vs_groceries" for f in facts)


def test_budget_fact_present(client):
    today = date.today()
    with SessionLocal() as db:
        db.add(Budget(category="food", monthly_limit=Decimal("5000")))
        db.commit()
    _seed_txn("1000.00", today, "ZOMATO ONLINE", override="food")
    facts = build_life_evidence(SessionLocal(), today=today)
    assert any(f.id == "budget:food" for f in facts)


def test_trip_fact_for_upcoming_trip_but_not_old_one(client):
    today = date.today()
    with SessionLocal() as db:
        db.add(
            TripBackup(
                id="trip-upcoming",
                title="Goa",
                payload={
                    "schema_version": 2,
                    "trip": {
                        "id": "trip-upcoming",
                        "title": "Goa",
                        "start_date": (today + timedelta(days=10)).isoformat(),
                        "end_date": (today + timedelta(days=14)).isoformat(),
                        "currency": "INR",
                        "status": "planned",
                    },
                    "activities": [],
                    "expenses": [],
                },
                revision=1,
                last_mutation_id="m1",
            )
        )
        db.add(
            TripBackup(
                id="trip-old",
                title="Old trip",
                payload={
                    "schema_version": 2,
                    "trip": {
                        "id": "trip-old",
                        "title": "Old trip",
                        "start_date": (today - timedelta(days=100)).isoformat(),
                        "end_date": (today - timedelta(days=90)).isoformat(),
                        "currency": "INR",
                        "status": "done",
                    },
                    "activities": [],
                    "expenses": [],
                },
                revision=1,
                last_mutation_id="m2",
            )
        )
        db.commit()
    facts = build_life_evidence(SessionLocal(), today=today)
    assert any(f.id == "trip:trip-upcoming" for f in facts)
    assert not any(f.id == "trip:trip-old" for f in facts)


def test_raising_builder_does_not_stop_others(client, monkeypatch):
    today = date.today()
    _set_income("50000")

    def _boom(*args, **kwargs):
        raise RuntimeError("boom")

    monkeypatch.setattr(life_evidence, "_card_facts", _boom)
    facts = build_life_evidence(SessionLocal(), today=today)
    assert any(f.id == "money:income" for f in facts)


def test_fact_count_stays_within_budget(client):
    today = date.today()
    _set_income("500000")
    for i in range(20):
        _seed_txn(f"{2000 + i}.00", today, f"MERCHANT {i}", override=f"cat{i}")
    facts = build_life_evidence(SessionLocal(), today=today)
    assert len(facts) <= 45
