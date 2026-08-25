"""Tests for transaction clock-time extraction and how the collector stamps it.

The bank's stated body time is preferred; the email's arrival instant is the
fallback when the body has no time, the model returns nothing, or the model is
unreachable. Time is cosmetic, so a failing extraction must never drop or defer
the transaction — it just falls back.
"""

from __future__ import annotations

from datetime import date, datetime, time, timezone

from app.services.expense_collector import ExpenseCollector
from app.mail.models import MailMessage
from app.services.expense_parsers.base import ParsedTxn
from app.services.expense_parsers.txn_time import extract_clock_time
from app.services.openclaw import OpenClawUnavailable


class _FakeClient:
    """Returns one canned chat_structured() reply, or raises OpenClawUnavailable."""

    def __init__(self, response: str | None = None, fail: bool = False) -> None:
        self.response = response
        self.fail = fail
        self.timeout = 120.0
        self.calls = 0

    def chat_structured(self, messages, schema, schema_name="response", temperature=0.0) -> str:
        self.calls += 1
        if self.fail:
            raise OpenClawUnavailable("down")
        return self.response


def _email(text: str = "Rs.10 debited at 14:41:02", internal_ms: int = 1_751_478_677_000) -> MailMessage:
    return MailMessage(
        message_id="m1",
        subject="alert",
        from_addr="alerts@hdfcbank.bank.in",
        received="Wed, 02 Jul 2026 14:41:07 +0530",
        text=text,
        raw_body=text,
        internal_date_ms=internal_ms,
    )


# -- extract_clock_time -------------------------------------------------------

def test_extract_returns_body_time_when_present():
    client = _FakeClient('{"explicit_time_present": true, "time_text": "at 14:41:02", "time_24h": "14:41:02"}')
    assert extract_clock_time("body", client) == time(14, 41, 2)


def test_extract_accepts_hh_mm_only():
    client = _FakeClient('{"explicit_time_present": true, "time_text": "13:26 IST", "time_24h": "13:26"}')
    assert extract_clock_time("body", client) == time(13, 26)


def test_extract_returns_none_when_no_explicit_time():
    client = _FakeClient('{"explicit_time_present": false, "time_text": "", "time_24h": ""}')
    assert extract_clock_time("body", client) is None


def test_extract_returns_none_on_unparseable_reply():
    assert extract_clock_time("body", _FakeClient("not json")) is None


def test_extract_returns_none_on_bad_time_value():
    client = _FakeClient('{"explicit_time_present": true, "time_text": "noon", "time_24h": "25:99"}')
    assert extract_clock_time("body", client) is None


def test_extract_returns_none_when_client_unavailable():
    client = _FakeClient(fail=True)
    assert extract_clock_time("body", client) is None


# -- collector stamping -------------------------------------------------------

class _OneTxnParser:
    """Yields a single txn for every email, so we can observe txn_at stamping."""

    name = "fake"
    gmail_query = "from:test"

    def parse(self, email: MailMessage) -> list[ParsedTxn]:
        return [ParsedTxn(txn_date=date(2026, 7, 2), amount=1, direction="debit", source="upi")]


class _FakeGmail:
    def __init__(self, emails: list[MailMessage]):
        self.account = "x@example.com"
        self.emails = {e.message_id: e for e in emails}

    def is_available(self) -> bool:
        return True

    def search(self, query: str, max_results: int = 100) -> list[dict]:
        return [{"id": mid} for mid in self.emails]

    def get_message(self, message_id: str) -> MailMessage:
        return self.emails[message_id]


def _as_naive_utc(dt: datetime) -> datetime:
    """Normalize to naive-UTC wall clock. Postgres TIMESTAMPTZ round-trips
    tz-aware; SQLite (the test backend) returns it naive — same instant, so
    strip the zone to compare backend-agnostically."""
    if dt.tzinfo is not None:
        dt = dt.astimezone(timezone.utc).replace(tzinfo=None)
    return dt


def _sync_one(client, internal_ms: int = 1_751_478_677_000):
    """Run a one-email sync and return (transactions_new, deferred, txn_at).

    txn_at is read inside the session so the ORM instance isn't accessed after
    the session closes (which would detach it)."""
    from app.database import SessionLocal

    with SessionLocal() as db:
        report = ExpenseCollector(
            db,
            _FakeGmail([_email(internal_ms=internal_ms)]),
            parsers=[_OneTxnParser()],
            openclaw=client,
        ).sync()
        (txn,) = report.created
        return report.transactions_new, report.transactions_deferred, txn.txn_at


def test_collector_stamps_body_time(client):
    fake = _FakeClient('{"explicit_time_present": true, "time_text": "at 14:41:02", "time_24h": "14:41:02"}')
    new, _deferred, txn_at = _sync_one(fake)
    assert new == 1
    # 14:41:02 IST on 2026-07-02 == 09:11:02 UTC.
    assert _as_naive_utc(txn_at) == datetime(2026, 7, 2, 9, 11, 2)


def test_collector_falls_back_to_email_arrival_when_no_body_time(client):
    fake = _FakeClient('{"explicit_time_present": false, "time_text": "", "time_24h": ""}')
    _new, _deferred, txn_at = _sync_one(fake, internal_ms=1_751_478_677_000)
    assert _as_naive_utc(txn_at) == datetime.utcfromtimestamp(1_751_478_677)


def test_collector_persists_txn_even_when_time_extraction_fails(client):
    fake = _FakeClient(fail=True)
    new, deferred, txn_at = _sync_one(fake)
    assert new == 1  # transaction still stored, not deferred
    assert deferred == 0
    # Time falls back to the email arrival instant.
    assert _as_naive_utc(txn_at) == datetime.utcfromtimestamp(1_751_478_677)
