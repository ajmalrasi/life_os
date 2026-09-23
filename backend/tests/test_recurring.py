"""Recurring-charge detection (Stage 4c): app/services/recurring.py."""

from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal

from app.database import SessionLocal
from app.repositories.expense_repo import ExpenseRepository
from app.services.expense_parsers.base import ParsedTxn
from app.services.recurring import detect_recurring_charges, normalize_counterparty


def _seed(rows: list[tuple[date, str, str]]):
    """rows: (txn_date, amount, counterparty)."""
    with SessionLocal() as db:
        repo = ExpenseRepository(db)
        for i, (day, amount, who) in enumerate(rows):
            repo.upsert(
                ParsedTxn(txn_date=day, amount=Decimal(amount), direction="debit",
                          source="upi", counterparty=who),
                message_id=f"m-{who}-{i}", dedup_key=f"k-{who}-{i}",
            )
        db.commit()


def test_monthly_subscription_is_detected(client):
    today = date.today()
    rows = [
        (today - timedelta(days=90), "999.00", "ANTHROPIC 4521098"),
        (today - timedelta(days=60), "999.00", "ANTHROPIC 8813221"),
        (today - timedelta(days=30), "999.00", "ANTHROPIC 1102938"),
        (today, "999.00", "ANTHROPIC 5567012"),
    ]
    _seed(rows)

    with SessionLocal() as db:
        results = detect_recurring_charges(db)

    matches = [r for r in results if normalize_counterparty(r.counterparty) == "anthropic"]
    assert len(matches) == 1
    charge = matches[0]
    assert charge.cadence == "monthly"
    assert charge.occurrences == 4
    assert charge.last_date == today
    assert charge.next_expected_date == today + timedelta(days=30)
    assert charge.price_change_pct == 0.0


def test_weekly_charge_is_detected(client):
    today = date.today()
    rows = [
        (today - timedelta(days=21), "250.00", "GYM MEMBERSHIP"),
        (today - timedelta(days=14), "250.00", "GYM MEMBERSHIP"),
        (today - timedelta(days=7), "250.00", "GYM MEMBERSHIP"),
        (today, "250.00", "GYM MEMBERSHIP"),
    ]
    _seed(rows)

    with SessionLocal() as db:
        results = detect_recurring_charges(db)

    matches = [r for r in results if normalize_counterparty(r.counterparty) == "gym membership"]
    assert len(matches) == 1
    assert matches[0].cadence == "weekly"


def test_irregular_gaps_are_not_flagged(client):
    today = date.today()
    rows = [
        (today - timedelta(days=95), "500.00", "RANDOM STORE"),
        (today - timedelta(days=60), "500.00", "RANDOM STORE"),
        (today - timedelta(days=12), "500.00", "RANDOM STORE"),
        (today, "500.00", "RANDOM STORE"),
    ]
    _seed(rows)

    with SessionLocal() as db:
        results = detect_recurring_charges(db)

    assert not any(normalize_counterparty(r.counterparty) == "random store" for r in results)


def test_amount_outside_tolerance_is_not_flagged(client):
    today = date.today()
    rows = [
        (today - timedelta(days=90), "100.00", "VARIABLE BILL"),
        (today - timedelta(days=60), "100.00", "VARIABLE BILL"),
        (today - timedelta(days=30), "500.00", "VARIABLE BILL"),  # >10% jump
        (today, "100.00", "VARIABLE BILL"),
    ]
    _seed(rows)

    with SessionLocal() as db:
        results = detect_recurring_charges(db)

    assert not any(normalize_counterparty(r.counterparty) == "variable bill" for r in results)


def test_fewer_than_three_occurrences_is_not_flagged(client):
    today = date.today()
    rows = [
        (today - timedelta(days=30), "999.00", "TOO NEW"),
        (today, "999.00", "TOO NEW"),
    ]
    _seed(rows)

    with SessionLocal() as db:
        results = detect_recurring_charges(db)

    assert not any(normalize_counterparty(r.counterparty) == "too new" for r in results)


def test_price_increase_is_reported(client):
    today = date.today()
    rows = [
        (today - timedelta(days=90), "999.00", "NETFLIX"),
        (today - timedelta(days=60), "999.00", "NETFLIX"),
        (today - timedelta(days=30), "999.00", "NETFLIX"),
        (today, "1089.00", "NETFLIX"),  # +9%, still within the 10% detection tolerance
    ]
    _seed(rows)

    with SessionLocal() as db:
        results = detect_recurring_charges(db)

    matches = [r for r in results if normalize_counterparty(r.counterparty) == "netflix"]
    assert len(matches) == 1
    assert matches[0].price_change_pct > 0


def test_recurring_endpoint_returns_detected_charges(client):
    today = date.today()
    rows = [
        (today - timedelta(days=90), "999.00", "ANTHROPIC"),
        (today - timedelta(days=60), "999.00", "ANTHROPIC"),
        (today - timedelta(days=30), "999.00", "ANTHROPIC"),
        (today, "999.00", "ANTHROPIC"),
    ]
    _seed(rows)

    resp = client.get("/expenses/recurring")
    assert resp.status_code == 200
    labels = [normalize_counterparty(r["counterparty"]) for r in resp.json()]
    assert "anthropic" in labels


def test_normalize_counterparty_strips_reference_numbers_and_case():
    assert normalize_counterparty("Anthropic 4521098") == "anthropic"
    assert normalize_counterparty("  ANTHROPIC   ") == "anthropic"
    assert normalize_counterparty("Anthropic 8813221") == normalize_counterparty("Anthropic 1102938")
