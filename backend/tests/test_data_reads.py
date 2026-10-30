"""Public data reads share Ask's services and never change source records."""

from datetime import date, datetime, timezone
from decimal import Decimal

import pytest
from sqlalchemy import select, text
from sqlalchemy.exc import OperationalError

from app.config import get_settings
from app.database import SessionLocal, get_read_db
from app.models.health import HealthRecord
from app.models.transaction import Transaction
from app.services.agent_tools import AgentTools


def seed():
    with SessionLocal() as db:
        db.add(
            HealthRecord(
                source_id="synthetic-health",
                type="steps",
                value=1234,
                unit="count",
                start_time=datetime(2025, 9, 2, tzinfo=timezone.utc),
                end_time=datetime(2025, 9, 2, 23, tzinfo=timezone.utc),
            )
        )
        for i, (amount, direction, category) in enumerate(
            [
                ("10.10", "debit", None),
                ("20.20", "debit", None),
                ("50.00", "debit", "emi"),
                ("100.00", "debit", "transfer"),
                ("200.00", "credit", None),
                ("20.00", "debit", "reversed_debit"),
            ]
        ):
            db.add(
                Transaction(
                    txn_date=date(2025, 9, 2),
                    amount=Decimal(amount),
                    currency="INR",
                    direction=direction,
                    category=category,
                    spend_category_override="food",
                    source="upi",
                    gmail_message_id=f"synthetic-{i}",
                    dedup_key=f"synthetic-{i}",
                )
            )
        db.commit()


def test_health_read_matches_ask_and_keeps_missing_values_unknown(client):
    seed()
    payload = {"start_date": "2025-09-01", "end_date": "2025-09-03"}
    response = client.get("/health/records", params=payload)
    assert response.status_code == 200
    with SessionLocal() as db:
        assert response.json() == AgentTools(db).health_records(**payload)
        assert len(list(db.scalars(select(HealthRecord)))) == 1
    rows = {r["day"]: r for r in response.json()["daily"]}
    assert rows["2025-09-02"]["steps"] == 1234
    assert rows["2025-09-01"]["steps"] is None


def test_health_end_only_query_is_resolved_relative_to_that_end(client):
    response = client.get(
        "/health/records", params={"days": 3, "end_date": "2025-09-03"}
    )
    assert response.status_code == 200
    assert response.json()["window"] == {
        "start": "2025-09-01",
        "end": "2025-09-03",
        "days": 3,
    }


def test_money_read_matches_ask_and_preserves_accounting_despite_row_limit(client):
    seed()
    payload = {"start_date": "2025-09-01", "end_date": "2025-09-03", "limit": 1}
    response = client.post("/expenses/query", json=payload)
    assert response.status_code == 200
    with SessionLocal() as db:
        assert response.json() == AgentTools(db)._run("query_money", payload)
        assert len(list(db.scalars(select(Transaction)))) == 6
    assert response.json()["summary"]["total"] == 80.3
    assert response.json()["summary"]["count"] == 3
    assert len(response.json()["rows"]) == 1


@pytest.mark.parametrize(
    "path,method,payload",
    [
        ("/health/records", "get", {"days": 91}),
        (
            "/health/records",
            "get",
            {"start_date": "2025-09-03", "end_date": "2025-09-01"},
        ),
        ("/health/records", "get", {"sql": "SELECT private"}),
        ("/expenses/query", "post", {"sql": "SELECT private"}),
        ("/expenses/query", "post", {"limit": 51}),
        ("/expenses/query", "post", {"compare_start_date": "2025-09-01"}),
    ],
)
def test_invalid_queries_are_rejected(client, path, method, payload):
    response = (
        client.get(path, params=payload)
        if method == "get"
        else client.post(path, json=payload)
    )
    assert response.status_code == 422


@pytest.mark.parametrize(
    "path,method", [("/health/records", "get"), ("/expenses/query", "post")]
)
def test_reads_follow_normal_api_auth(client, monkeypatch, path, method):
    monkeypatch.setattr(get_settings(), "api_token", "synthetic-api-token")
    call = getattr(client, method)
    kwargs = {"json": {}} if method == "post" else {}
    assert call(path, **kwargs).status_code == 401
    assert (
        call(
            path, headers={"Authorization": "Bearer synthetic-api-token"}, **kwargs
        ).status_code
        == 200
    )


def test_read_session_blocks_writes_and_resets_connection(client):
    session = get_read_db()
    db = next(session)
    try:
        with pytest.raises(OperationalError):
            db.execute(text("CREATE TABLE forbidden_write (value INTEGER)"))
    finally:
        session.close()
    with SessionLocal() as db:
        db.execute(text("CREATE TABLE synthetic_after_read (value INTEGER)"))
        db.rollback()
