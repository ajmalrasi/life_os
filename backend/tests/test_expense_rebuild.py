"""Tests for the scoped historical rebuild path (/expenses/rebuild-llm):

- ExpenseRepository.delete_since must only ever touch the sources it's told
  to, since a rebuild through the LLM live-alert parser has no way to
  regenerate monthly statement-PDF data (cc_statement / bank_account).
- ExpenseCollector, given ``since_date``, must query an explicit date window
  and never touch the shared incremental sync cursor.
- The API endpoint must apply that same scoping end to end.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from unittest.mock import patch

from app.database import SessionLocal
from app.models.transaction import Transaction
from app.repositories.expense_repo import ExpenseRepository
from app.repositories.statement_repo import StatementRepository
from app.services.expense_collector import ExpenseCollector
from app.services.expense_parsers.hdfc_upi import HdfcUpiParser
from tests.test_expenses import _FakeGmail, _hdfc_email


def _seed_txn(
    db, *, source: str, txn_date: date, amount: str = "100.00", mid: str = "x1", account: str | None = None
) -> Transaction:
    t = Transaction(
        txn_date=txn_date,
        amount=Decimal(amount),
        direction="debit",
        source=source,
        account=account,
        counterparty="X",
        gmail_message_id=mid,
        dedup_key=f"{source}:{txn_date}:{mid}",
    )
    db.add(t)
    db.flush()
    return t


# --- ExpenseRepository.delete_since -----------------------------------------------


def test_delete_since_only_touches_scoped_sources_and_date_range(client):
    with SessionLocal() as db:
        in_scope_id = _seed_txn(db, source="upi", txn_date=date(2026, 2, 1), mid="a").id
        out_of_scope_source_id = _seed_txn(db, source="cc_statement", txn_date=date(2026, 2, 1), mid="b").id
        out_of_scope_date_id = _seed_txn(db, source="upi", txn_date=date(2025, 12, 1), mid="c").id
        db.commit()

        deleted = ExpenseRepository(db).delete_since(
            date(2026, 1, 1), sources=StatementRepository.LIVE_SOURCES
        )
        db.commit()
        assert deleted == 1

    with SessionLocal() as db:
        remaining_ids = {t.id for t in db.query(Transaction).all()}
    assert in_scope_id not in remaining_ids
    assert out_of_scope_source_id in remaining_ids
    assert out_of_scope_date_id in remaining_ids


def test_live_sources_excludes_statement_sources():
    """A rebuild scoped to LIVE_SOURCES must never be able to delete
    statement-PDF-derived data — there'd be no way to regenerate it."""
    assert "cc_statement" not in StatementRepository.LIVE_SOURCES
    assert "bank_account" not in StatementRepository.LIVE_SOURCES


def test_delete_since_account_prefix_scopes_to_one_bank(client):
    """A per-bank rebuild (since dates can differ wildly by bank's statement
    coverage) must never delete a *different* bank's rows in the same call —
    it has no way to regenerate them in that run."""
    with SessionLocal() as db:
        hdfc_id = _seed_txn(db, source="upi", txn_date=date(2026, 6, 1), mid="a", account="HDFC Savings ..7672").id
        axis_id = _seed_txn(db, source="upi", txn_date=date(2026, 6, 1), mid="b", account="Axis Savings ..4402").id
        db.commit()

        deleted = ExpenseRepository(db).delete_since(
            date(2026, 1, 1), sources=StatementRepository.LIVE_SOURCES, account_prefix="HDFC"
        )
        db.commit()
        assert deleted == 1

    with SessionLocal() as db:
        remaining_ids = {t.id for t in db.query(Transaction).all()}
    assert hdfc_id not in remaining_ids
    assert axis_id in remaining_ids


# --- ExpenseCollector(since_date=...) ----------------------------------------------


def test_since_date_uses_explicit_after_query_not_cursor_or_backfill(client):
    gmail = _FakeGmail([_hdfc_email(mid="m1")])
    with SessionLocal() as db:
        ExpenseCollector(
            db, gmail, parsers=[HdfcUpiParser()], since_date=date(2026, 1, 1)
        ).sync()
    assert gmail.queries == [f"{HdfcUpiParser.gmail_query} after:2026/01/01"]


def test_since_date_does_not_advance_shared_cursor(client):
    gmail = _FakeGmail([_hdfc_email(mid="m1", internal_ms=1_751_000_000_000)])
    with SessionLocal() as db:
        repo = ExpenseRepository(db)
        before = repo.get_cursor(f"gmail_expenses:{gmail.account}")
        ExpenseCollector(
            db, gmail, parsers=[HdfcUpiParser()], since_date=date(2026, 1, 1)
        ).sync()
        after = repo.get_cursor(f"gmail_expenses:{gmail.account}")
    assert before == after == 0


def test_since_date_scoped_run_uses_only_the_given_parsers(client):
    """Passing parsers=[...] must bypass the full registry — a rebuild scoped
    to the LLM parser must never also invoke the regex parsers."""
    gmail = _FakeGmail([_hdfc_email(mid="m1")])
    with SessionLocal() as db:
        report = ExpenseCollector(
            db, gmail, parsers=[HdfcUpiParser()], since_date=date(2026, 1, 1)
        ).sync()
    assert report.parsers_run == 1


# --- /expenses/rebuild-llm endpoint -------------------------------------------------


def test_rebuild_endpoint_deletes_only_scoped_rows(client):
    with SessionLocal() as db:
        _seed_txn(db, source="upi", txn_date=date(2026, 2, 1), mid="a")
        _seed_txn(db, source="cc_statement", txn_date=date(2026, 2, 1), mid="b")
        db.commit()

    # No gmail account configured in tests -> collector reports unavailable,
    # so this exercises the delete step without needing a real OpenClaw call.
    with patch("app.services.gmail_client.shutil.which", return_value=None):
        resp = client.post("/expenses/rebuild-llm", params={"since": "2026-01-01"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["transactions_deleted"] == 1
    assert body["transactions_new"] == 0

    with SessionLocal() as db:
        remaining_sources = {t.source for t in db.query(Transaction).all()}
    assert remaining_sources == {"cc_statement"}


def test_rebuild_endpoint_bank_param_scopes_delete_to_that_bank(client):
    with SessionLocal() as db:
        _seed_txn(db, source="upi", txn_date=date(2026, 2, 1), mid="a", account="HDFC Savings ..7672")
        _seed_txn(db, source="upi", txn_date=date(2026, 2, 1), mid="b", account="Axis Savings ..4402")
        db.commit()

    with patch("app.services.gmail_client.shutil.which", return_value=None):
        resp = client.post("/expenses/rebuild-llm", params={"since": "2026-01-01", "bank": "HDFC"})
    assert resp.status_code == 200
    assert resp.json()["transactions_deleted"] == 1

    with SessionLocal() as db:
        remaining_accounts = {t.account for t in db.query(Transaction).all()}
    assert remaining_accounts == {"Axis Savings ..4402"}


def test_rebuild_endpoint_rejects_unknown_bank(client):
    resp = client.post("/expenses/rebuild-llm", params={"since": "2026-01-01", "bank": "SBI"})
    assert resp.status_code == 422
