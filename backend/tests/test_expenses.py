"""Tests for the Gmail expense pipeline: HTML parsing, the HDFC UPI parser,
idempotent dedup, and the /expenses API surface.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from unittest.mock import patch

from app.services.expense_collector import ExpenseCollector, derive_dedup_key
from app.services.expense_parsers import get_parsers
from app.services.expense_parsers.base import BankEmail
from app.services.expense_parsers.hdfc_upi import HdfcUpiParser
from app.services.gmail_client import strip_html

# Real HDFC RuPay-CC UPI alert body (HTML), trimmed to the salient block.
_HDFC_HTML = """
<html><body>
<table><tr><td>
Dear Customer, Greetings from HDFC Bank! We're sharing this alert to help you
quickly check a recent UPI transaction. <b>Transaction Details:</b>
Rs.190.00 has been debited from your RuPay Credit Card (ending 5473)
Paid to q186411044@ybl Date: 02-07-26
UPI Transaction Reference Number: 125669193205
Important Note: If you made this transaction, no action is needed.
</td></tr></table>
</body></html>
"""


def _hdfc_email(html: str = _HDFC_HTML, mid: str = "m1", internal_ms: int = 1_751_478_677_000) -> BankEmail:
    return BankEmail(
        message_id=mid,
        subject="You have done a UPI txn. Check details!",
        from_addr="HDFC Bank InstaAlerts <alerts@hdfcbank.bank.in>",
        received="Thu, 02 Jul 2026 23:51:17 +0530",
        text=strip_html(html),
        raw_body=html,
        internal_date_ms=internal_ms,
    )


def test_strip_html_removes_markup():
    text = strip_html(_HDFC_HTML)
    assert "<td>" not in text and "<b>" not in text
    assert "Rs.190.00 has been debited" in text


def test_hdfc_parser_extracts_fields():
    (txn,) = HdfcUpiParser().parse(_hdfc_email())
    assert txn.amount == Decimal("190.00")
    assert txn.direction == "debit"
    assert txn.source == "upi"
    assert txn.account == "HDFC RuPay CC ..5473"
    assert txn.counterparty == "q186411044@ybl"
    assert txn.reference == "125669193205"
    assert txn.txn_date == date(2026, 7, 2)


def test_hdfc_parser_ignores_unrelated_email():
    junk = _hdfc_email(html="<p>Your statement is ready. No amount here.</p>")
    assert HdfcUpiParser().parse(junk) == []


def test_dedup_key_prefers_reference():
    (txn,) = HdfcUpiParser().parse(_hdfc_email())
    key = derive_dedup_key(txn, "m1")
    assert key == "upi:ref:125669193205"
    # Same payment seen in a different email → same key (no double count).
    assert derive_dedup_key(txn, "m2") == key


def test_registry_has_hdfc():
    assert any(p.name == "hdfc_upi" for p in get_parsers())


class _FakeGmail:
    """Stub GmailClient. Serves ``emails`` and records the queries it received,
    so tests can assert the collector switches from backfill to incremental."""

    def __init__(self, emails: list[BankEmail]):
        self.account = "x@example.com"
        self.emails = {e.message_id: e for e in emails}
        self.queries: list[str] = []

    def is_available(self) -> bool:
        return True

    def search(self, query: str, max_results: int = 100) -> list[dict]:
        self.queries.append(query)
        return [{"id": mid} for mid in self.emails]

    def get_message(self, message_id: str) -> BankEmail:
        return self.emails[message_id]


def _run_sync(gmail):
    from app.database import SessionLocal

    with SessionLocal() as db:
        return ExpenseCollector(db, gmail, backfill_days=90).sync()


def test_collector_persists_and_is_idempotent(client):
    report1 = _run_sync(_FakeGmail([_hdfc_email(mid="m1")]))
    assert report1.transactions_new == 1
    assert report1.cursor_epoch == 1_751_478_677  # internalDate ms // 1000

    # Second run over the same email creates nothing new.
    report2 = _run_sync(_FakeGmail([_hdfc_email(mid="m1")]))
    assert report2.transactions_new == 0

    rows = client.get("/expenses?days=3650").json()
    assert len(rows) == 1
    assert rows[0]["amount"] == "190.00"
    assert rows[0]["counterparty"] == "q186411044@ybl"
    assert rows[0]["source"] == "upi"


def test_collector_incremental_cursor(client):
    # First run: cursor unset → backfill window.
    first = _FakeGmail([_hdfc_email(mid="m1", internal_ms=1_751_000_000_000)])
    _run_sync(first)
    assert first.queries and "newer_than" in first.queries[0]

    # Second run: cursor set → `after:` window (pull only new).
    second = _FakeGmail([_hdfc_email(mid="m1", internal_ms=1_751_000_000_000)])
    _run_sync(second)
    assert second.queries and "after:1751000000" in second.queries[0]


def test_new_since_endpoint(client):
    _run_sync(_FakeGmail([_hdfc_email(mid="m1")]))
    all_rows = client.get("/expenses/new?after_id=0").json()
    assert len(all_rows) == 1
    last_id = all_rows[0]["id"]
    # Nothing new after the last seen id.
    assert client.get(f"/expenses/new?after_id={last_id}").json() == []


def test_sync_endpoint_disabled_without_account(client):
    # No gmail_account configured in tests → collector reports unavailable.
    with patch("app.services.gmail_client.shutil.which", return_value=None):
        resp = client.post("/expenses/sync")
    assert resp.status_code == 200
    assert resp.json()["transactions_new"] == 0
