"""Tests for LLM-based payee categorization (fallback for what the keyword
rules and user-authored payee_labels miss).

Best-effort like the other OpenClaw features: unreachable/junk responses must
never raise, and must never produce a label in one of the spend-exclusion
categories (those are only ever assigned by services.reconcile).
"""

from __future__ import annotations

import json
from datetime import date
from decimal import Decimal

from app.database import SessionLocal
from app.models.transaction import Transaction
from app.repositories.payee_label_repo import PayeeLabelRepository
from app.services.openclaw import OpenClawUnavailable
from app.services.payee_classifier import (
    classify_payee,
    suggest_labels_for_new_payees,
)


class _FakeClient:
    """Returns a canned chat_structured() response, or raises if `fail` is set."""

    def __init__(self, responses=None, fail: bool = False) -> None:
        self.responses = list(responses or [])
        self.fail = fail
        self.timeout = 120.0
        self.calls = 0

    def chat_structured(self, messages, schema, schema_name="response", temperature=0.0) -> str:
        self.calls += 1
        if self.fail:
            raise OpenClawUnavailable("down")
        return self.responses.pop(0)


def _txn(counterparty: str, direction: str = "debit", category: str | None = None, amount: str = "100.00") -> Transaction:
    return Transaction(
        txn_date=date(2026, 7, 1),
        amount=Decimal(amount),
        direction=direction,
        counterparty=counterparty,
        category=category,
        source="upi",
        gmail_message_id="m1",
        dedup_key=f"test:{counterparty}:{direction}:{category}",
    )


# --- classify_payee -------------------------------------------------------------


def test_classify_payee_parses_valid_response():
    client = _FakeClient(['{"category": "groceries", "confidence": "high", "reason": "grocery store"}'])
    result = classify_payee("NESTO HYPERMARKET", client=client)
    assert result.category == "groceries"
    assert result.confidence == "high"


def test_classify_payee_returns_none_when_unavailable():
    assert classify_payee("SOME PAYEE", client=_FakeClient(fail=True)) is None


def test_classify_payee_returns_none_on_unparseable_response():
    assert classify_payee("SOME PAYEE", client=_FakeClient(["not json"])) is None


def test_classify_payee_rejects_disallowed_category():
    """A schema-constrained model shouldn't be able to return this, but the
    parser must not trust it blindly if it somehow did — "transfer" and the
    other reconciliation categories must only come from services.reconcile."""
    client = _FakeClient(['{"category": "transfer", "confidence": "high", "reason": "x"}'])
    assert classify_payee("CRED CLUB", client=client) is None


# --- suggest_labels_for_new_payees -----------------------------------------------


def test_suggest_labels_creates_active_label_for_high_confidence(client):
    with SessionLocal() as db:
        txns = [_txn("NESTO HYPERMARKET (paytm.s1@pty)")]
        fake = _FakeClient(['{"category": "groceries", "confidence": "high", "reason": "grocery store"}'])
        created = suggest_labels_for_new_payees(db, txns, client=fake)
        assert created == 1

        label = PayeeLabelRepository(db).get_by_pattern("NESTO HYPERMARKET", "substring")
        assert label is not None
        assert label.category == "groceries"
        assert label.active is True


def test_suggest_labels_creates_inactive_label_for_low_confidence(client):
    with SessionLocal() as db:
        txns = [_txn("q559887345@ybl")]
        fake = _FakeClient(['{"category": "other", "confidence": "low", "reason": "bare VPA, no signal"}'])
        created = suggest_labels_for_new_payees(db, txns, client=fake)
        assert created == 1

        label = PayeeLabelRepository(db).get_by_pattern("q559887345@ybl", "substring")
        assert label is not None
        assert label.active is False


def test_suggest_labels_skips_credits(client):
    with SessionLocal() as db:
        txns = [_txn("SOME SENDER", direction="credit")]
        fake = _FakeClient()
        created = suggest_labels_for_new_payees(db, txns, client=fake)
        assert created == 0
        assert fake.calls == 0


def test_suggest_labels_skips_already_categorized_transactions(client):
    """Reconciliation runs first and tags transfers/returns with a category —
    those must never reach the LLM (wasted call, and would otherwise risk
    overwriting a reconciliation-owned category with a guess)."""
    with SessionLocal() as db:
        txns = [_txn("CRED Club (cred.club@axisb)", category="transfer")]
        fake = _FakeClient()
        created = suggest_labels_for_new_payees(db, txns, client=fake)
        assert created == 0
        assert fake.calls == 0


def test_suggest_labels_skips_payee_with_existing_label(client):
    with SessionLocal() as db:
        PayeeLabelRepository(db).create(pattern="MEHBOOBA", category="legal_fees", match_type="substring")
        db.commit()

        txns = [_txn("MEHBOOBA PARAMBIL (jessyparambil97@oksbi)")]
        fake = _FakeClient()
        created = suggest_labels_for_new_payees(db, txns, client=fake)
        assert created == 0
        assert fake.calls == 0


def test_suggest_labels_deduplicates_same_payee_in_one_run(client):
    with SessionLocal() as db:
        txns = [
            _txn("VAR GROCERY (paytm.s1@pty)", amount="100.00"),
            _txn("VAR GROCERY (paytm.s2@pty)", amount="200.00"),
        ]
        fake = _FakeClient(['{"category": "groceries", "confidence": "high", "reason": "grocery"}'])
        created = suggest_labels_for_new_payees(db, txns, client=fake)
        assert created == 1
        assert fake.calls == 1


def test_suggest_labels_stops_when_openclaw_unavailable(client):
    with SessionLocal() as db:
        txns = [_txn("SOME PAYEE")]
        created = suggest_labels_for_new_payees(db, txns, client=_FakeClient(fail=True))
        assert created == 0


def test_suggest_labels_disabled_by_setting(client, monkeypatch):
    from app.services import payee_classifier

    monkeypatch.setattr(payee_classifier.settings, "openclaw_payee_classification_enabled", False)
    with SessionLocal() as db:
        txns = [_txn("SOME PAYEE")]
        fake = _FakeClient()
        created = suggest_labels_for_new_payees(db, txns, client=fake)
        assert created == 0
        assert fake.calls == 0
