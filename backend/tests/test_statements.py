"""Tests for statement summary parsing and password derivation.

Fixture text is trimmed from real statements (amounts/structure preserved).
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from app.services.pdf_unlock import parse_overrides, password_candidates
from app.services.statement_parsers.axis import AxisStatementParser
from app.services.statement_parsers.hdfc import HdfcStatementParser
from app.services.statement_parsers.icici import IciciStatementParser
from app.services.statement_parsers.sbi import SbiStatementParser

_HDFC = """Tata Neu Plus HDFC Bank Credit Card Statement
AJMAL RASI Credit Card No. 652925XXXXXX5473
Statement Date 19 Jun, 2026
Billing Period 20 May, 2026 - 19 Jun, 2026
PREVIOUS STATEMENT DUES FINANCE CHARGES TOTAL AMOUNT DUE
_ C18,835.00
C17,842.51 C17,843.00 + C18,835.73 + C0.00 =
MINIMUM DUE DUE DATE
C950.00 09 Jul, 2026
Domestic Transactions
20/05/2026| 12:29 UPI-SHIHAB K P C 156.00 l
24/05/2026| 15:37 EVERSUB INDIA PRIVATELCALICUT + 12 C 576.00 l
30/05/2026| 09:34 BPPY CC PAYMENT DP016150093417qJNgl (Ref# ST261510083000010090193) + C 17,843.00 l
"""

_AXIS = """Flipkart Axis Bank VISA Credit Card Statement
AJMAL RASI K P
Total Payment Due Minimum Payment Due Statement Period Payment Due Date Statement Generation Date
18,699.25 Dr 10,516.00 Dr 17/05/2026 - 15/06/2026 05/07/2026 15/06/2026
440006******3068 244,000.00 164,471.75 73,200.00 Auto-Debit facility on 18605005555
=Total Payment Due Making only the minimum payment every
1,255.02 Dr 1,255.02 41,379.00 59,268.77 0.00 809.48 18,699.25 Dr over years
30/05/2026 BBPS PAYMENT RECEIVED - DP016150093417DRCDSQ 1,255.02 Cr 0.00 Dr
09/06/2026 MYNTRA,BANGALORE CLOTH STORES 3,750.00 Dr 281.00 Cr
"""

_ICICI = """MR AJMAL P
STATEMENT DATE
June 16, 2026
PAYMENT DUE DATE
July 4, 2026
STATEMENT SUMMARY
Total Amount due
`21,353.48 = + + -
Minimum Amount due CREDIT SUMMARY
`1,070.00
6528XXXXXXXX3006
19/05/2026 13442059612 UPI-613978022793-AM AUTOM OTIVE 377 18,880.00
30/05/2026 13504819520 BBPS Payment received 0 17,725.98 CR
Statement period : May 17, 2026 to June 16, 2026
Previous Balance Purchases / Charges Cash Advances Payments / Credits
`17,725.98 `21,353.48 `0.00 `17,725.98
"""

_SBI = """AJMAL RASI KP Credit Card Number
XXXX XXXX XXXX XX70
*Total Amount Due ( ` )
20,636.00
**Minimum Amount Due( ` )
STMT No. : C26061942157
CKYC No. : 60042650739530 413.00
Statement Date
57,000.00 11,400.00 24 Jun 2026
Payment Due Date
36,364.32 11,400.00 14 Jul 2026
14,657.54 14,758.00 20,736.14 0.00 20,636.00
for Statement Period: 25 May 26 to 24 Jun 26
30 May 26 PAYMENT RECEIVED 000DP016150093417gOPKcF 14,658.00 C
24 May 26 CITY PETROLEUM KOTTAKKAL IN 1,010.00 D
Your BPCL SBI Card OCTANE Monthly Statement -Jun 2026
"""


def test_hdfc_parser():
    p = HdfcStatementParser().parse(_HDFC)
    assert p.card_last4 == "5473"
    assert p.total_due == Decimal("18835.00")
    assert p.min_due == Decimal("950.00")
    assert p.due_date == date(2026, 7, 9)
    assert p.total_spend == Decimal("18835.73")
    assert len(p.line_items) == 3
    upi = p.line_items[0]
    assert (upi.description, upi.amount, upi.direction) == ("UPI-SHIHAB K P", Decimal("156.00"), "debit")
    # "+ 12" tail is reward points, not a credit marker; bare "+" IS a credit.
    assert p.line_items[1].direction == "debit"
    assert p.line_items[2].direction == "credit"


def test_axis_parser():
    p = AxisStatementParser().parse(_AXIS)
    assert p.card_last4 == "3068"
    assert p.total_due == Decimal("18699.25")
    assert p.min_due == Decimal("10516.00")
    assert p.due_date == date(2026, 7, 5)
    assert p.total_spend == Decimal("59268.77")
    assert [i.direction for i in p.line_items] == ["credit", "debit"]


def test_icici_parser_uses_filename_for_label():
    p = IciciStatementParser().parse(_ICICI, filename="6528XXXXXXXX3006_925424_Retail_Coral_A.pdf")
    assert p.card_label == "ICICI Coral"
    assert p.card_last4 == "3006"
    assert p.total_due == Decimal("21353.48")
    assert p.due_date == date(2026, 7, 4)
    assert p.total_spend == Decimal("21353.48")
    assert len(p.line_items) == 2
    assert p.line_items[1].direction == "credit"


def test_sbi_parser():
    p = SbiStatementParser().parse(_SBI)
    assert p.total_due == Decimal("20636.00")
    assert p.min_due == Decimal("413.00")
    assert p.due_date == date(2026, 7, 14)
    assert p.period_start == date(2026, 5, 25)
    assert len(p.line_items) == 2
    assert {i.direction for i in p.line_items} == {"credit", "debit"}


def test_password_candidates():
    ov = parse_overrides("sbi:010719931970")
    assert password_candidates("AJMAL RASI", "01071993", ov, "hdfc")[0] == "AJMA0107"
    assert "ajma0107" in password_candidates("AJMAL RASI", "01071993", ov, "icici")
    assert password_candidates("AJMAL RASI", "01071993", ov, "sbi")[0] == "010719931970"


def test_statement_collector_stores_summary_without_transactions(client, monkeypatch):
    """Statement line items are observable but never enter the transaction feed."""
    from app.database import SessionLocal
    from app.repositories.expense_repo import ExpenseRepository
    from app.repositories.statement_repo import StatementRepository
    from app.services.statement_collector import StatementCollector

    class FakeGmail:
        account = "test@example.com"

        def is_available(self):
            return True

        def search(self, query, max_results):
            if "cc.statements@axis.bank.in" in query:
                return [{"id": "axis-summary-only"}]
            return []

        def list_pdf_attachments(self, message_id):
            return [{"attachmentId": "a1", "filename": "axis.pdf"}]

        def download_attachment(self, message_id, attachment_id, destination):
            return True

    monkeypatch.setattr("app.services.statement_collector.extract_pdf_text", lambda *_: _AXIS)

    with SessionLocal() as db:
        report = StatementCollector(db, FakeGmail(), "AJMAL RASI", "01071993").sync()
        stored = StatementRepository(db).range(date(2026, 6, 1), date(2026, 6, 30))

        assert report.statements_new == 1
        assert report.line_items_ingested == 0
        assert "2 parsed line item(s) ignored" in report.message
        assert len(stored) == 1
        assert stored[0].total_due == Decimal("18699.25")
        assert stored[0].line_items_total == 2
        assert stored[0].line_items_ingested == 0
        assert ExpenseRepository(db).range(date(2026, 5, 1), date(2026, 6, 30)) == []


def test_bank_account_parser_balance_delta():
    from app.services.statement_parsers.bank_accounts import IciciAccountParser

    text = """Statement of Transactions in Savings Account XXXXXXXX3317 in INR
DATE MODE PARTICULARS DEPOSITS WITHDRAWALS BALANCE
01-05-2026 262.10
B/F
UPI/John Doe/repay
05-05-2026 199.00 63.10
10-05-2026 500.00 563.10
"""
    p = IciciAccountParser().parse(text)
    assert p.card_last4 == "3317"
    assert [(i.direction, str(i.amount)) for i in p.line_items] == [
        ("debit", "199.00"),   # balance fell 262.10 -> 63.10
        ("credit", "500.00"),  # balance rose 63.10 -> 563.10
    ]


def test_reconcile_lend_return_and_transfers(client):
    from datetime import date
    from decimal import Decimal

    from app.database import SessionLocal
    from app.repositories.expense_repo import ExpenseRepository
    from app.services.expense_parsers.base import ParsedTxn
    from app.services.reconcile import run_reconciliation

    with SessionLocal() as db:
        repo = ExpenseRepository(db)

        def put(day, amt, direction, who, key):
            repo.upsert(
                ParsedTxn(txn_date=day, amount=Decimal(amt), direction=direction,
                          source="bank_account", counterparty=who),
                message_id="m-" + key, dedup_key=key,
            )

        put(date(2026, 5, 1), "5000.00", "debit", "UPI-SHIHAB K P", "d1")      # lent
        put(date(2026, 5, 9), "5000.00", "credit", "Mr SHIHAB K P", "c1")      # returned
        put(date(2026, 5, 2), "777.00", "debit", "UPI-ZOMATO", "d2")           # real spend
        put(date(2026, 5, 3), "17843.00", "debit", "BBPS CC PAYMENT DP01", "d3")  # transfer
        db.commit()
        report = run_reconciliation(db)
        assert report.transfers_tagged == 1
        assert report.return_pairs == 1

        # Idempotent: nothing new on a second run.
        again = run_reconciliation(db)
        assert again.transfers_tagged == 0 and again.return_pairs == 0

    # Excluded from spend analysis; the Zomato spend remains.
    trends = client.get("/statements/trends?months=3").json()
    cats = {c["category"]: float(c["total"]) for c in trends["aggregate_by_category"]}
    assert float(trends["aggregate_debits"]) == 777.00
    assert "food" in cats and cats["food"] == 777.00


def test_cred_upi_debit_is_a_transfer_not_credit_confusion():
    """A UPI debit to CRED (used specifically to pay CC bills) is a transfer.
    Must not false-positive on unrelated words containing "cred" as a substring."""
    from app.services.reconcile import _TRANSFER_DEBIT_PAT

    assert _TRANSFER_DEBIT_PAT.search("UPI-someuser@cred")
    assert _TRANSFER_DEBIT_PAT.search("CRED-RZP payment")
    assert _TRANSFER_DEBIT_PAT.search("DREAMPLUG TECHNOLOGIES PVT LTD")
    # These contain "cred" as a substring but are not the CRED app.
    assert not _TRANSFER_DEBIT_PAT.search("CREDIT CARD ANNUAL FEE REVERSAL")
    assert not _TRANSFER_DEBIT_PAT.search("ACCREDITED MERCHANT SERVICES")
    assert not _TRANSFER_DEBIT_PAT.search("someuser@credpay")


def test_is_self_matches_owner_name_and_vpa_truncation_tolerant():
    """Self-transfers carry the owner's own name/VPA as the counterparty, which
    banks clip differently on each leg. Match either-way prefix; reject others."""
    from app.services.reconcile import _is_self, _owner_identities

    handles, names = _owner_identities(["AJMAL RASI K P", "ajmalrasi@upi"])

    # Full name + VPA (the outgoing HDFC leg).
    assert _is_self("AJMAL RASI K P (ajmalrasi@upi)", None, handles, names)
    # Clipped name, no VPA (the incoming Axis leg) — owner name is a prefix.
    assert _is_self("AJMAL RAS (AJMAL RAS)", None, handles, names)
    # PSP suffix drift on the handle still matches (local part is what counts).
    assert _is_self("SELF (ajmalrasi@okhdfcbank)", None, handles, names)
    # "UPI-NAME-vpa" layout, even with a *different* VPA handle (kpajmalrasi@):
    # the name still resolves after stripping the UPI marker.
    assert _is_self("UPI-AJMAL RASI K P-kpajmalrasi@", None, handles, names)

    # Different people who merely share a short prefix must NOT match.
    assert not _is_self("AJMAL RASHEED", None, handles, names)
    assert not _is_self("AJMAL TRADERS", None, handles, names)
    assert not _is_self("ZOMATO LTD", None, handles, names)
    # No configured identities -> never self.
    assert not _is_self("AJMAL RASI K P", None, set(), set())


def test_tag_transfers_tags_self_transfer_both_directions(client):
    """A self-transfer (own name) is a transfer in either direction, from any
    bank — the debit out of one account and the credit into another."""
    from datetime import date
    from decimal import Decimal

    from app.database import SessionLocal
    from app.repositories.expense_repo import ExpenseRepository
    from app.services.expense_parsers.base import ParsedTxn
    from app.services.reconcile import EXCLUDED_FROM_SPEND, tag_transfers

    with SessionLocal() as db:
        repo = ExpenseRepository(db)

        def put(day, amt, direction, who, key, acct):
            repo.upsert(
                ParsedTxn(txn_date=day, amount=Decimal(amt), direction=direction,
                          source="upi", counterparty=who, account=acct),
                message_id="m-" + key, dedup_key=key,
            )

        put(date(2026, 7, 7), "30000.00", "debit", "AJMAL RASI K P (ajmalrasi@upi)",
            "self-d", "HDFC Savings ..7672")
        put(date(2026, 7, 7), "30000.00", "credit", "AJMAL RAS (AJMAL RAS)",
            "self-c", "Axis Bank Ltd. Savings ..4402")
        put(date(2026, 7, 7), "450.00", "debit", "UPI-ZOMATO", "spend", "HDFC Savings ..7672")
        db.commit()

        n = tag_transfers(db, owner_identities=["AJMAL RASI K P", "ajmalrasi@upi"])
        db.commit()
        assert n == 2  # both self legs, not the Zomato spend

        rows = {t.dedup_key: t.category for t in repo.range(date(2026, 7, 7), date(2026, 7, 7))}
        assert rows["self-d"] == "transfer" and rows["self-d"] in EXCLUDED_FROM_SPEND
        assert rows["self-c"] == "transfer"
        assert rows["spend"] is None  # real spend untouched

        # Idempotent: a second pass tags nothing new.
        assert tag_transfers(db, owner_identities=["AJMAL RASI K P"]) == 0


def test_hdfc_account_parser_explicit_columns():
    from app.services.statement_parsers.bank_accounts import HdfcAccountParser

    text = """Account Number : 50100421827672 Tirur
Txn Date Narration Withdrawals Deposits Closing Balance
04/05/2026 UPI-MUJEEBRAHMAN-mujeebpnm123@ 400.00 0.00 91,524.23
okhdfcbank-CNRB0000033-122585491852-UPI
07/05/2026 IMPS-612700298798-Mobikwik-KKBK-xxxx 0.00 1,000.00 16,070.91
xx0617-remarks Value Dt 07/05/2026 Ref 612700298798
"""
    p = HdfcAccountParser().parse(text)
    assert p.card_last4 == "7672"
    assert len(p.line_items) == 2
    assert (p.line_items[0].direction, str(p.line_items[0].amount)) == ("debit", "400.00")
    assert (p.line_items[1].direction, str(p.line_items[1].amount)) == ("credit", "1000.00")


def test_sbi_account_parser_explicit_columns_and_account_number():
    from app.services.statement_parsers.bank_accounts import SbiAccountParser

    text = """Customer XXXXXXX4705
SAVING ACCOUNT
XXXXXXX9646
Name of the Account Holder Mr. AJMAL RASI K P
28-05-26 UPI/DR/614881399594/ABDUSSAM/UCBA/lustretiru/UPI - 0 200.00 1766.58
UPI/DR/614831262030/Muhammad/AIRP/smon65790@/UPI
28-05-26 - 0 700.00 1066.58
28-05-26 UPI/REV/614831262030 - 700.00 0 1766.58
"""
    p = SbiAccountParser().parse(text)
    # Must pick the real account number (SAVING ACCOUNT block), not the
    # masked "Customer XXXXXXX4705" id that appears earlier in the doc.
    assert p.card_last4 == "9646"
    assert len(p.line_items) == 3
    assert p.line_items[0].direction == "debit" and str(p.line_items[0].amount) == "200.00"
    # Buffered narration line attaches to the following dateless-desc row.
    assert "Muhammad" in p.line_items[1].description
    assert p.line_items[1].direction == "debit" and str(p.line_items[1].amount) == "700.00"
    assert p.line_items[2].direction == "credit" and "REV" in p.line_items[2].description


def test_reconcile_reversal_by_ref_number(client):
    """A UPI debit that immediately reverses (bank-side failure, not a person
    returning money) must be excluded from spend even though the reversal
    credit's narration shares no name text with the original debit — only the
    UPI reference number links them."""
    from datetime import date
    from decimal import Decimal

    from app.database import SessionLocal
    from app.repositories.expense_repo import ExpenseRepository
    from app.services.expense_parsers.base import ParsedTxn
    from app.services.reconcile import reconcile_reversals

    with SessionLocal() as db:
        repo = ExpenseRepository(db)
        repo.upsert(
            ParsedTxn(
                txn_date=date(2026, 5, 28), amount=Decimal("700.00"), direction="debit",
                source="bank_account",
                raw_snippet="UPI/DR/614831262030/Muhammad/AIRP/smon65790@/UPI",
            ),
            message_id="m-rev-d", dedup_key="rev-d",
        )
        repo.upsert(
            ParsedTxn(
                txn_date=date(2026, 5, 28), amount=Decimal("700.00"), direction="credit",
                source="bank_account", raw_snippet="UPI/REV/614831262030",
            ),
            message_id="m-rev-c", dedup_key="rev-c",
        )
        db.commit()
        n = reconcile_reversals(db)
        db.commit()
        assert n == 2

        again = reconcile_reversals(db)
        assert again == 0

    trends = client.get("/statements/trends?months=3").json()
    assert float(trends["aggregate_debits"]) == 0.0


def test_reconcile_returns_ignores_institutional_descriptors(client):
    """Two ACH clearing-house debits/credits with identical generic text must
    NOT be paired as lend/return — that text is printed for every transaction
    on that rail, not evidence of a specific person returning money."""
    from datetime import date
    from decimal import Decimal

    from app.database import SessionLocal
    from app.repositories.expense_repo import ExpenseRepository
    from app.services.expense_parsers.base import ParsedTxn
    from app.services.reconcile import reconcile_returns

    with SessionLocal() as db:
        repo = ExpenseRepository(db)
        repo.upsert(
            ParsedTxn(txn_date=date(2026, 6, 1), amount=Decimal("53000.00"), direction="debit",
                      source="bank_account", counterparty="ACH D- Indian Clearing"),
            message_id="m-ach-d", dedup_key="ach-d",
        )
        repo.upsert(
            ParsedTxn(txn_date=date(2026, 6, 20), amount=Decimal("53000.00"), direction="credit",
                      source="bank_account", counterparty="ACH D- Indian Clearing"),
            message_id="m-ach-c", dedup_key="ach-c",
        )
        db.commit()
        pairs = reconcile_returns(db)
        assert pairs == 0

    # Both remain real (uncategorized) transactions; the debit counts as spend.
    trends = client.get("/statements/trends?months=3").json()
    assert float(trends["aggregate_debits"]) == 53000.00


def test_analysis_drops_cards_whose_due_date_has_passed(client):
    """Upcoming card dues must exclude cards whose due date is already over —
    a lapsed bill should not keep accumulating into total/min due or the list."""
    from datetime import date, timedelta
    from decimal import Decimal

    from app.database import SessionLocal
    from app.repositories.statement_repo import StatementRepository
    from app.services.statement_parsers.base import ParsedStatement

    today = date.today()

    def stmt(bank, last4, due, total, mn):
        return ParsedStatement(
            bank=bank, card_label=f"{bank} {last4}", card_last4=last4,
            statement_date=due - timedelta(days=20) if due else today,
            period_start=None, period_end=None,
            total_due=Decimal(total), min_due=Decimal(mn),
            due_date=due, total_spend=None,
        )

    with SessionLocal() as db:
        repo = StatementRepository(db)
        # Upcoming — counts.
        repo.add_statement(stmt("hdfc", "1111", today + timedelta(days=5), "1000", "100"), "m1")
        # Due today — the boundary still counts.
        repo.add_statement(stmt("axis", "2222", today, "2000", "200"), "m2")
        # Lapsed — must be dropped.
        repo.add_statement(stmt("icici", "3333", today - timedelta(days=3), "9999", "999"), "m3")
        db.commit()

    data = client.get("/statements/analysis").json()
    assert float(data["total_due_all_cards"]) == 3000.00  # 1000 + 2000, not 12999
    assert float(data["min_due_all_cards"]) == 300.00
    assert data["next_due_date"] == today.isoformat()
    last4s = {c["card_last4"] for c in data["cards"]}
    assert last4s == {"1111", "2222"}
