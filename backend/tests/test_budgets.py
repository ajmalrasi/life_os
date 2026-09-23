"""GET/PUT/DELETE /budgets and the pace math behind them (Stage 4b)."""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from app.database import SessionLocal
from app.repositories.expense_repo import ExpenseRepository
from app.services.expense_parsers.base import ParsedTxn


def _seed_food_spend(amount: str, when: date | None = None):
    with SessionLocal() as db:
        repo = ExpenseRepository(db)
        repo.upsert(
            ParsedTxn(
                txn_date=when or date.today(),
                amount=Decimal(amount),
                direction="debit",
                source="upi",
                counterparty="ZOMATO ONLINE",
            ),
            message_id=f"m-{amount}-{when}",
            dedup_key=f"k-{amount}-{when}",
        )
        db.commit()


def test_list_budgets_is_empty_by_default(client):
    resp = client.get("/budgets")
    assert resp.status_code == 200
    assert resp.json() == []


def test_set_budget_upserts_and_returns_pace(client):
    resp = client.put("/budgets/food", json={"monthly_limit": "5000.00"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["category"] == "food"
    assert body["monthly_limit"] == "5000.00"
    assert body["spent_so_far"] == "0.00" or body["spent_so_far"] == "0"
    assert body["on_pace_to_exceed"] is False

    listed = client.get("/budgets").json()
    assert [b["category"] for b in listed] == ["food"]

    # Upsert again with a different limit — same category, not a duplicate row.
    resp2 = client.put("/budgets/food", json={"monthly_limit": "6000.00"})
    assert resp2.json()["monthly_limit"] == "6000.00"
    assert len(client.get("/budgets").json()) == 1


def test_pace_reflects_month_to_date_spend(client):
    client.put("/budgets/food", json={"monthly_limit": "1000.00"})
    _seed_food_spend("500.00")

    body = client.get("/budgets").json()[0]
    assert Decimal(body["spent_so_far"]) == Decimal("500.00")
    assert body["days_elapsed"] == date.today().day
    # Spending 500 on day 1 of a 30-day month projects to 15000 — comfortably
    # over any reasonable limit; the exact projection is asserted structurally
    # (it must be >= spent_so_far) rather than pinned to today's calendar day.
    assert Decimal(body["projected_month_total"]) >= Decimal("500.00")


def test_pace_flags_projected_overspend(client):
    client.put("/budgets/food", json={"monthly_limit": "1.00"})
    _seed_food_spend("500.00")

    body = client.get("/budgets").json()[0]
    assert body["on_pace_to_exceed"] is True
    assert body["projected_exceed_day"] is not None
    assert 1 <= body["projected_exceed_day"] <= body["days_in_month"]


def test_delete_budget(client):
    client.put("/budgets/food", json={"monthly_limit": "5000.00"})
    assert client.delete("/budgets/food").status_code == 204
    assert client.get("/budgets").json() == []
    assert client.delete("/budgets/food").status_code == 404


def test_get_life_context_includes_budgets_only_when_set(client):
    from app.database import SessionLocal as _SessionLocal
    from app.services.chat_service import ChatService

    with _SessionLocal() as db:
        context = ChatService(db).build_context(7)
    assert context["budgets"] is None

    client.put("/budgets/food", json={"monthly_limit": "5000.00"})
    _seed_food_spend("200.00")

    with _SessionLocal() as db:
        context = ChatService(db).build_context(7)
    assert context["budgets"] is not None
    assert len(context["budgets"]) == 1
    assert context["budgets"][0]["category"] == "food"
    assert context["budgets"][0]["spent_so_far"] == 200.0
