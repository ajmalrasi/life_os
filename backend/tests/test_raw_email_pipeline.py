"""Tests for the two-phase historical rebuild (fetch raw emails, then process
them through the LLM chain in bounded batches).

The split exists so a dropped connection during a multi-month backfill only
costs the batch in flight — fetching is pure Gmail I/O (idempotent on
message_id) and processing never re-hits Gmail, so both halves are safe to
retry independently.
"""

from __future__ import annotations

import json
from datetime import date

from app.database import SessionLocal
from app.models.raw_bank_email import RawBankEmail
from app.models.transaction import Transaction
from app.repositories.raw_email_repo import RawEmailRepository
from app.services.expense_parsers.base import BankEmail
from app.services.openclaw import OpenClawUnavailable
from app.services.raw_email_pipeline import (
    fetch_raw_emails,
    process_raw_emails,
    transactions_from_raw_emails,
)
from tests.test_expenses import _FakeGmail


class _FakeClient:
    def __init__(self, responses=None, fail_at: int | None = None, healthy: bool = True) -> None:
        self.responses = list(responses or [])
        self.fail_at = fail_at
        self.timeout = 120.0
        self.calls = 0
        self.healthy = healthy

    def health(self) -> bool:
        return self.healthy

    def chat_structured(self, messages, schema, schema_name="response", temperature=0.0) -> str:
        idx = self.calls
        self.calls += 1
        if self.fail_at == idx:
            raise OpenClawUnavailable("down")
        return self.responses[idx]


_YES_GATE = '{"is_transaction": true, "reason": "a debit happened"}'
_NO_GATE = '{"is_transaction": false, "reason": "promo"}'


def _fields(**overrides) -> str:
    data = {
        "direction": "debit",
        "bank": "HDFC",
        "amount": 110.0,
        "counterparty_name": "RAFEEQUE PATTATH",
        "counterparty_vpa": "paytm.s1hzt87@pty",
        "is_card": False,
        "is_upi": True,
        "is_rupay_upi": False,
        "last4": "7672",
        "txn_date": "2026-07-03",
        "reference": "125721524336",
    }
    data.update(overrides)
    return json.dumps(data)


def _email(mid: str = "m1", internal_ms: int = 1_751_000_000_000, text: str = "some email") -> BankEmail:
    return BankEmail(
        message_id=mid,
        subject="whatever",
        from_addr="HDFC Bank <alerts@hdfcbank.bank.in>",
        received="Fri, 03 Jul 2026 12:00:00 +0530",
        text=text,
        raw_body=text,
        internal_date_ms=internal_ms,
    )


# --- fetch_raw_emails ---------------------------------------------------------------


def test_fetch_saves_new_emails_and_builds_correct_query(client):
    gmail = _FakeGmail([_email(mid="a"), _email(mid="b")])
    with SessionLocal() as db:
        report = fetch_raw_emails(db, gmail, bank="Axis", since=date(2026, 1, 1))
    assert report.emails_found == 2
    assert report.emails_saved == 2
    assert gmail.queries == ["from:alerts@axis.bank.in after:2026/01/01"]

    with SessionLocal() as db:
        saved = db.query(RawBankEmail).all()
    assert {r.message_id for r in saved} == {"a", "b"}
    assert all(r.bank == "Axis" for r in saved)


def test_fetch_is_idempotent_on_message_id(client):
    gmail = _FakeGmail([_email(mid="a")])
    with SessionLocal() as db:
        fetch_raw_emails(db, gmail, bank="Axis", since=date(2026, 1, 1))
        second = fetch_raw_emails(db, gmail, bank="Axis", since=date(2026, 1, 1))
    assert second.emails_found == 1
    assert second.emails_saved == 0  # already saved, no-op

    with SessionLocal() as db:
        assert db.query(RawBankEmail).count() == 1


def test_fetch_rejects_unknown_bank(client):
    import pytest

    gmail = _FakeGmail([])
    with SessionLocal() as db, pytest.raises(KeyError):
        fetch_raw_emails(db, gmail, bank="Nonexistent", since=date(2026, 1, 1))


# --- process_raw_emails --------------------------------------------------------------


def _seed_raw(db, *, mid: str, bank: str = "HDFC", internal_ms: int = 1_751_000_000_000, text: str = "txn") -> RawBankEmail:
    return RawEmailRepository(db).create(
        message_id=mid, bank=bank, subject="s", from_addr="f", received="r",
        text=text, internal_date_ms=internal_ms,
    )


def test_process_creates_transaction_and_marks_processed(client):
    with SessionLocal() as db:
        _seed_raw(db, mid="a")
        db.commit()

        fake = _FakeClient([_YES_GATE, _fields()])
        report = process_raw_emails(db, limit=15, client=fake)
        assert report.processed == 1
        assert report.transactions_created == 1
        assert report.remaining == 0

        row = db.query(RawBankEmail).filter_by(message_id="a").one()
        assert row.processed_at is not None

        txn = db.query(Transaction).filter_by(gmail_message_id="a").one()
        assert txn.amount == 110
        assert txn.counterparty == "RAFEEQUE PATTATH (paytm.s1hzt87@pty)"


def test_process_skips_debit_already_covered_by_statement(client):
    """Regression: this pipeline's since-date can span months a monthly
    statement already fully covers (e.g. a savings account with no statement
    history of its own shares a bank domain with cards that do have one) —
    without this guard, re-ingesting that range via the LLM catch-all
    duplicates every one of those already-settled transactions."""
    from decimal import Decimal

    from app.models.transaction import Transaction as TxnModel

    with SessionLocal() as db:
        db.add(TxnModel(
            txn_date=date(2026, 7, 3), amount=Decimal("110.00"), direction="debit",
            source="cc_statement", counterparty="RAFEEQUE PATTATH",
            gmail_message_id="stmt-1", dedup_key="stmt-dup-test",
        ))
        _seed_raw(db, mid="a")
        db.commit()

        fake = _FakeClient([_YES_GATE, _fields()])  # amount 110.00, same date
        report = process_raw_emails(db, limit=15, client=fake)
        assert report.transactions_created == 0

        assert db.query(Transaction).filter_by(gmail_message_id="a").count() == 0


def test_process_skips_non_transaction_but_still_marks_processed(client):
    with SessionLocal() as db:
        _seed_raw(db, mid="a")
        db.commit()

        fake = _FakeClient([_NO_GATE])
        report = process_raw_emails(db, limit=15, client=fake)
        assert report.transactions_created == 0

        row = db.query(RawBankEmail).filter_by(message_id="a").one()
        assert row.processed_at is not None


def test_process_respects_limit_and_leaves_remainder(client):
    with SessionLocal() as db:
        for i in range(3):
            _seed_raw(db, mid=f"m{i}", internal_ms=1_751_000_000_000 + i)
        db.commit()

        fake = _FakeClient([_NO_GATE, _NO_GATE])
        report = process_raw_emails(db, limit=2, client=fake)
        assert report.processed == 2
        assert report.remaining == 1


def test_process_stops_without_marking_processed_when_openclaw_unhealthy(client):
    """Regression: if OpenClaw goes unreachable mid-batch (e.g. Ollama
    unloading the model under memory pressure), rows must NOT be marked
    processed — LlmBankParser's own best-effort fallback treats "couldn't
    ask" the same as "asked, and it's not a transaction", so without this
    check every remaining row in the batch would be silently and permanently
    marked done despite never having been genuinely classified."""
    with SessionLocal() as db:
        _seed_raw(db, mid="a")
        _seed_raw(db, mid="b")
        db.commit()

        fake = _FakeClient([], healthy=False)
        report = process_raw_emails(db, limit=15, client=fake)
        assert report.processed == 0
        assert report.stopped_openclaw_unavailable is True
        assert report.remaining == 2  # neither row touched

        rows = db.query(RawBankEmail).all()
    assert all(r.processed_at is None for r in rows)


def test_process_stops_partway_when_openclaw_becomes_unhealthy_mid_batch(client):
    with SessionLocal() as db:
        _seed_raw(db, mid="a", internal_ms=1)
        _seed_raw(db, mid="b", internal_ms=2)
        db.commit()

        fake = _FakeClient([_NO_GATE])  # healthy for row "a" only
        fake.health = lambda: fake.calls == 0  # unhealthy once "a"'s call has happened
        report = process_raw_emails(db, limit=15, client=fake)
        assert report.processed == 1
        assert report.stopped_openclaw_unavailable is True

        rows = {r.message_id: r.processed_at for r in db.query(RawBankEmail).all()}
    assert rows["a"] is not None
    assert rows["b"] is None


def test_process_oldest_first(client):
    with SessionLocal() as db:
        _seed_raw(db, mid="newer", internal_ms=2_000_000_000_000)
        _seed_raw(db, mid="older", internal_ms=1_000_000_000_000)
        db.commit()

        fake = _FakeClient([_NO_GATE])
        process_raw_emails(db, limit=1, client=fake)

        processed = {r.message_id for r in db.query(RawBankEmail).all() if r.processed_at is not None}
    assert processed == {"older"}


def test_process_reprocessing_replaces_not_duplicates_transaction(client):
    """Reprocessing the same raw email (e.g. after a parser fix) must replace
    its old transaction, not leave a stale duplicate behind."""
    with SessionLocal() as db:
        _seed_raw(db, mid="a")
        db.commit()

        fake1 = _FakeClient([_YES_GATE, _fields(amount=100.0)])
        process_raw_emails(db, limit=15, client=fake1)

        # Manually reset processed_at to simulate re-running after a fix.
        row = db.query(RawBankEmail).filter_by(message_id="a").one()
        row.processed_at = None
        db.commit()

        fake2 = _FakeClient([_YES_GATE, _fields(amount=200.0)])
        process_raw_emails(db, limit=15, client=fake2)

        txns = db.query(Transaction).filter_by(gmail_message_id="a").all()
    assert len(txns) == 1
    assert txns[0].amount == 200


def test_process_bank_filter_only_processes_matching_bank(client):
    """``remaining`` is scoped to the same bank filter as the batch, so a
    caller looping "until my bank's queue is empty" gets the right signal —
    not polluted by a different bank's unrelated backlog."""
    with SessionLocal() as db:
        _seed_raw(db, mid="hdfc1", bank="HDFC")
        _seed_raw(db, mid="axis1", bank="Axis")
        db.commit()

        fake = _FakeClient([_NO_GATE])
        report = process_raw_emails(db, bank="HDFC", limit=15, client=fake)
        assert report.processed == 1
        assert report.remaining == 0  # HDFC queue is empty; Axis is untouched

        rows = {r.message_id: r.processed_at for r in db.query(RawBankEmail).all()}
    assert rows["hdfc1"] is not None
    assert rows["axis1"] is None


# --- API endpoints -------------------------------------------------------------------


def test_fetch_endpoint_rejects_unknown_bank(client):
    resp = client.post("/expenses/rebuild-llm/fetch", params={"since": "2026-01-01", "bank": "Nonexistent"})
    assert resp.status_code == 422


def test_fetch_endpoint_no_gmail_account_returns_zero(client):
    from unittest.mock import patch

    with patch("app.services.gmail_client.shutil.which", return_value=None):
        resp = client.post("/expenses/rebuild-llm/fetch", params={"since": "2026-01-01", "bank": "HDFC"})
    assert resp.status_code == 200
    assert resp.json() == {"bank": "HDFC", "emails_found": 0, "emails_saved": 0}


def test_process_endpoint_rejects_unknown_bank(client):
    resp = client.post("/expenses/rebuild-llm/process", params={"bank": "Nonexistent"})
    assert resp.status_code == 422


def test_process_endpoint_with_no_rows_is_a_no_op(client):
    resp = client.post("/expenses/rebuild-llm/process", params={"bank": "HDFC"})
    assert resp.status_code == 200
    assert resp.json() == {
        "processed": 0,
        "transactions_created": 0,
        "remaining": 0,
        "stopped_openclaw_unavailable": False,
    }


def test_finalize_endpoint_returns_ok_with_no_transactions(client):
    resp = client.post("/expenses/rebuild-llm/finalize")
    assert resp.status_code == 200
    assert resp.json()["labels_created"] == 0


# --- transactions_from_raw_emails ------------------------------------------------


def test_transactions_from_raw_emails_excludes_unrelated_history(client):
    """Regression: finalize used to scan the *entire* transaction table (via
    since_id(0, ...)) because Transaction.category is None on essentially
    every ordinary row regardless of age, not just newly rebuilt ones — that
    made a rebuild's "finalize" step run the LLM categorizer over months of
    already-settled, unrelated payees. Scoping by gmail_message_id against
    raw_bank_emails must return only what this pipeline actually touched."""
    from decimal import Decimal

    with SessionLocal() as db:
        # Unrelated pre-existing transaction, never part of this pipeline.
        old = Transaction(
            txn_date=date(2026, 1, 1), amount=Decimal("50.00"), direction="debit",
            source="upi", counterparty="OLD UNRELATED PAYEE",
            gmail_message_id="not-in-raw-emails", dedup_key="old-1",
        )
        db.add(old)
        db.commit()

        _seed_raw(db, mid="a")
        db.commit()
        fake = _FakeClient([_YES_GATE, _fields()])
        process_raw_emails(db, limit=15, client=fake)

        rows = transactions_from_raw_emails(db)
    assert {r.gmail_message_id for r in rows} == {"a"}


def test_transactions_from_raw_emails_empty_when_no_raw_emails(client):
    with SessionLocal() as db:
        assert transactions_from_raw_emails(db) == []
