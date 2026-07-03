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


# Real HDFC Diners "A payment was made" alert body (HTML), trimmed.
_HDFC_DINERS_HTML = """
<html><body>
Dear Customer, Greetings from HDFC Bank. We would like to inform you that
Rs. 5156.50 has been debited from your HDFC Bank Credit Card ending 6116
towards WWW MOBIKWIK COM on 29 Jun, 2026 at 14:41:02 . To check your
available balance, outstanding amount, or view recent transactions, you may
use: Mycards: https://mycards.hdfc.bank.in
</body></html>
"""


def _diners_email(html: str = _HDFC_DINERS_HTML, mid: str = "d1") -> BankEmail:
    return BankEmail(
        message_id=mid,
        subject="A payment was made using your Credit Card",
        from_addr="HDFC Bank InstaAlerts <alerts@hdfcbank.bank.in>",
        received="Mon, 29 Jun 2026 14:41:02 +0530",
        text=strip_html(html),
        raw_body=html,
    )


def test_hdfc_diners_live_parser_extracts_fields():
    from app.services.expense_parsers.hdfc_diners_live import HdfcDinersLiveParser

    (txn,) = HdfcDinersLiveParser().parse(_diners_email())
    assert txn.amount == Decimal("5156.50")
    assert txn.direction == "debit"
    assert txn.source == "hdfc_card_live"
    assert txn.account == "HDFC Diners Privilege CC ..6116"
    assert txn.counterparty == "WWW MOBIKWIK COM"
    assert txn.txn_date == date(2026, 6, 29)


def test_hdfc_diners_live_parser_ignores_unrelated_email():
    from app.services.expense_parsers.hdfc_diners_live import HdfcDinersLiveParser

    junk = _diners_email(html="<p>Your statement is ready.</p>")
    assert HdfcDinersLiveParser().parse(junk) == []


def test_registry_has_hdfc_diners_live():
    assert any(p.name == "hdfc_diners_live" for p in get_parsers())


def test_live_source_dedup_covers_both_upi_and_diners_live(client):
    """A statement line matching EITHER live source (upi or hdfc_card_live) by
    date+amount must be recognized as already-seen, not just the UPI one."""
    from app.database import SessionLocal
    from app.repositories.statement_repo import StatementRepository

    with SessionLocal() as db:
        repo = ExpenseCollector(
            db,
            client=type("_C", (), {"is_available": lambda self: True})(),  # unused here
            backfill_days=90,
        ).repo
        from app.services.expense_parsers.base import ParsedTxn

        repo.upsert(
            ParsedTxn(
                txn_date=date(2026, 6, 29), amount=Decimal("5156.50"), direction="debit",
                source="hdfc_card_live",
            ),
            message_id="live-diners-1", dedup_key="hdfc_card_live:test1",
        )
        db.commit()
        srepo = StatementRepository(db)
        assert srepo.upi_txn_exists(date(2026, 6, 29), Decimal("5156.50")) is True
        assert srepo.upi_txn_exists(date(2026, 6, 30), Decimal("5156.50")) is False


def test_live_alert_skipped_if_statement_already_ingested(client):
    """If a statement line for a transaction was already ingested (e.g. a live
    parser gets backfilled after that statement period was processed), the
    live alert for the same real transaction must not create a duplicate."""
    from app.database import SessionLocal
    from app.repositories.expense_repo import ExpenseRepository
    from app.services.expense_parsers.base import ParsedTxn

    with SessionLocal() as db:
        repo = ExpenseRepository(db)
        repo.upsert(
            ParsedTxn(txn_date=date(2026, 6, 29), amount=Decimal("5156.50"), direction="debit",
                      source="cc_statement", counterparty="WWW MOBIKWIK COM"),
            message_id="stmt-1", dedup_key="stmt-dup-test",
        )
        db.commit()
        assert repo.statement_txn_exists(date(2026, 6, 29), Decimal("5156.50")) is True

    report = _run_sync(_FakeGmail([_diners_email(mid="d1")]))
    assert report.transactions_new == 0


# Real HDFC SAVINGS ACCOUNT (···7672) UPI debit alert — user-provided example.
_HDFC_BANK_DEBIT_HTML = """
<html><body>
Dear Customer,
Greetings from HDFC Bank!
Rs.385.00 is debited from your account ending 7672 towards VPA paytm.s1g5ro3@pty (VAR GROCERY) on 30-06-26.
UPI transaction reference no.: 125522470351.
If you did not authorize this transaction, please report it immediately.
</body></html>
"""

# Real HDFC SAVINGS ACCOUNT credit alert (different wording from the debit).
_HDFC_BANK_CREDIT_HTML = """
<html><body>
Dear Customer, Greetings from HDFC Bank! We're writing to inform you that
Rs.5000.00 has been successfully credited to your HDFC Bank account ending in 7672.
Transaction Details:
a. Date: 01-07-26
b. Sender: MEHBOOBA PARAMBIL (VPA: jessyparambil97@oksbi)
c. UPI Reference No.: 618271180521
</body></html>
"""


def _bank_upi_email(html: str, mid: str = "b1", subject: str = "You have done a UPI txn. Check details!") -> BankEmail:
    return BankEmail(
        message_id=mid,
        subject=subject,
        from_addr="HDFC Bank InstaAlerts <alerts@hdfcbank.bank.in>",
        received="Tue, 30 Jun 2026 12:00:00 +0530",
        text=strip_html(html),
        raw_body=html,
    )


def test_hdfc_bank_upi_debit_parses_correctly():
    from app.services.expense_parsers.hdfc_bank_upi import HdfcBankUpiParser

    (txn,) = HdfcBankUpiParser().parse(_bank_upi_email(_HDFC_BANK_DEBIT_HTML))
    assert txn.amount == Decimal("385.00")
    assert txn.direction == "debit"
    assert txn.source == "upi"
    assert txn.account == "HDFC Savings ..7672"
    assert txn.counterparty == "VAR GROCERY (paytm.s1g5ro3@pty)"
    assert txn.txn_date == date(2026, 6, 30)


def test_hdfc_bank_upi_credit_parses_correctly():
    """Real credit alerts arrive under a differently-worded subject (no
    "UPI" in it) — use that real subject so a regression in gmail_query
    that stops fetching these emails would be caught here too."""
    from app.services.expense_parsers.hdfc_bank_upi import HdfcBankUpiParser

    email = _bank_upi_email(
        _HDFC_BANK_CREDIT_HTML, mid="b2", subject="Account update for your HDFC Bank A/c"
    )
    assert HdfcBankUpiParser.gmail_query.count("Account update") == 1
    (txn,) = HdfcBankUpiParser().parse(email)
    assert txn.amount == Decimal("5000.00")
    assert txn.direction == "credit"
    assert txn.account == "HDFC Savings ..7672"
    assert txn.counterparty == "MEHBOOBA PARAMBIL (jessyparambil97@oksbi)"
    assert txn.txn_date == date(2026, 7, 1)


def test_hdfc_bank_upi_does_not_match_rupay_cc_alert():
    """This new bank-account parser must not accidentally fire on the
    existing RuPay credit-card UPI alert (different wording, shares subject)."""
    from app.services.expense_parsers.hdfc_bank_upi import HdfcBankUpiParser

    assert HdfcBankUpiParser().parse(_hdfc_email()) == []


def test_rupay_parser_does_not_match_bank_account_alert():
    """And vice versa: the RuPay parser must not fire on the bank-account
    alert's "is debited" wording (it requires "has been debited")."""
    assert HdfcUpiParser().parse(_bank_upi_email(_HDFC_BANK_DEBIT_HTML)) == []


def test_registry_has_hdfc_bank_upi():
    assert any(p.name == "hdfc_bank_upi" for p in get_parsers())


# Real Axis Bank savings-account UPI debit alert — user-provided example.
_AXIS_BANK_DEBIT_HTML = """
<html><body>
28-06-2026

Dear Ajmal Rasi K P,

Here's the summary of your transaction:

Amount Debited:
INR 70.00

Account Number:
XX4402

Date &amp; Time:
28-06-26, 19:17:43 IST

Transaction Info:
UPI/P2M/191742697112/ASMABI P C

If this transaction was not initiated by you:
To block UPI:

SMS BLOCKUPI &lt;Customer ID&gt; to +919951860002 from your registered mobile number.
</body></html>
"""


def _axis_bank_upi_email(html: str = _AXIS_BANK_DEBIT_HTML, mid: str = "ax1") -> BankEmail:
    return BankEmail(
        message_id=mid,
        subject="INR 70.00 was debited from your A/c no. XX4402.",
        from_addr="Axis Bank Alerts <alerts@axis.bank.in>",
        received="Sun, 28 Jun 2026 19:17:43 +0530",
        text=strip_html(html),
        raw_body=html,
    )


def test_axis_bank_upi_parses_real_example():
    from app.services.expense_parsers.axis_bank_upi import AxisBankUpiParser

    (txn,) = AxisBankUpiParser().parse(_axis_bank_upi_email())
    assert txn.amount == Decimal("70.00")
    assert txn.direction == "debit"
    assert txn.source == "upi"
    assert txn.account == "Axis Savings ..4402"
    assert txn.counterparty == "ASMABI P C"
    assert txn.txn_date == date(2026, 6, 28)


def test_axis_bank_upi_ignores_unrelated_email():
    from app.services.expense_parsers.axis_bank_upi import AxisBankUpiParser

    junk = _axis_bank_upi_email(html="<p>Your statement is ready.</p>")
    assert AxisBankUpiParser().parse(junk) == []


def test_registry_has_axis_bank_upi():
    assert any(p.name == "axis_bank_upi" for p in get_parsers())
