"""Savings-account e-statement parsers (bank-account UPI/debits, not cards).

Two extraction strategies, depending on what the bank's PDF actually renders
as text:

- **Balance-delta** (ICICI, Axis): deposits/withdrawals are position-only
  columns that plain text extraction can't distinguish, but every row carries
  the post-transaction balance — if the balance went down, it was a debit.
- **Explicit columns** (HDFC, SBI): the bank prints separate
  withdrawal/deposit (or credit/debit) figures on the row itself, which is
  more reliable when available.

SBI's rows are irregular: some transaction narrations render on their own
line *before* the date+amounts row instead of on it (a pdfplumber artifact of
the source table's layout), so unmatched lines are buffered and used as the
description for the next dateless-narration row.
"""

from __future__ import annotations

import re
from datetime import date, datetime
from decimal import Decimal

from app.services.statement_parsers.base import ParsedStatement, StatementTxn

_HEADERish = re.compile(r"DATE MODE|PARTICULARS|Page \d|Total:|Legends|Statement of Transactions", re.I)


def _dec(s: str) -> Decimal:
    return Decimal(s.replace(",", ""))


def _build_statement(bank: str, label: str, last4: str | None, items: list[StatementTxn]) -> ParsedStatement:
    dates = [i.txn_date for i in items]
    debits = sum((i.amount for i in items if i.direction == "debit"), Decimal(0))
    return ParsedStatement(
        bank=bank,
        card_label=label,
        card_last4=last4,
        statement_date=max(dates) if dates else None,
        period_start=min(dates) if dates else None,
        period_end=max(dates) if dates else None,
        total_due=None,
        min_due=None,
        due_date=None,
        total_spend=debits or None,
        line_items=items,
    )


# -- balance-delta (ICICI, Axis) -------------------------------------------------

# Bare row: date + amount [+ balance] only ("05-05-2026 199.00 63.10"); tried
# first so a lazy description group can't swallow the amount column.
_ROW_BARE = re.compile(r"^(\d{2}-\d{2}-\d{4})\s+([\d,]+\.\d{2})(?:\s+([\d,]+\.\d{2}))?\s*$")
_ROW_DESC = re.compile(r"^(\d{2}-\d{2}-\d{4})\s+(.+?)\s+([\d,]+\.\d{2})(?:\s+([\d,]+\.\d{2}))?\s*$")


def _match_delta_row(line: str):
    m = _ROW_BARE.match(line)
    if m:
        dm, a1, a2 = m.groups()
        return dm, None, a1, a2
    m = _ROW_DESC.match(line)
    if m:
        return m.groups()
    return None


def _parse_balance_delta(text: str) -> list[StatementTxn]:
    items: list[StatementTxn] = []
    prev_balance: Decimal | None = None
    buffer: list[str] = []
    for line in text.splitlines():
        line = line.strip()
        if not line or _HEADERish.search(line):
            continue
        m = _match_delta_row(line)
        if not m:
            buffer.append(line)
            continue
        dm, inline_desc, a1, a2 = m
        txn_date = datetime.strptime(dm, "%d-%m-%Y").date()
        if a2 is None:
            if prev_balance is None:
                prev_balance = _dec(a1)
                buffer = []
            continue
        amount, balance = _dec(a1), _dec(a2)
        if prev_balance is None:
            prev_balance = balance
            buffer = []
            continue
        direction = "debit" if balance < prev_balance else "credit"
        desc = (inline_desc or " ".join(buffer[-3:]))[:256].strip() or "bank txn"
        items.append(StatementTxn(txn_date=txn_date, description=desc, amount=amount, direction=direction))
        prev_balance = balance
        buffer = []
    return items


class IciciAccountParser:
    bank = "icici_acct"
    txn_source = "bank_account"
    gmail_query = "from:estatement@icici.bank.in has:attachment"

    def parse(self, text: str, filename: str = "") -> ParsedStatement:
        m = re.search(r"Savings (?:A/c|Account) X+(\d{4})", text)
        items = _parse_balance_delta(text)
        return _build_statement(self.bank, "ICICI Savings", m.group(1) if m else None, items)


class AxisAccountParser:
    bank = "axis_acct"
    txn_source = "bank_account"
    gmail_query = "from:statements@axis.bank.in subject:statement has:attachment"

    def parse(self, text: str, filename: str = "") -> ParsedStatement:
        m = re.search(r"a/c no\. X+(\d{4})", text)
        items = _parse_balance_delta(text)
        return _build_statement(self.bank, "Axis Savings", m.group(1) if m else None, items)


# -- explicit withdrawal/deposit columns (HDFC) ----------------------------------

# "04/05/2026 UPI-MUJEEBRAHMAN-mujeebpnm123@ 400.00 0.00 91,524.23"
# date, narration (continues on following lines, ignored), withdrawal, deposit, balance
_HDFC_ROW = re.compile(
    r"^(\d{2}/\d{2}/\d{4})\s+(.+?)\s+([\d,]+\.\d{2})\s+([\d,]+\.\d{2})\s+([\d,]+\.\d{2})\s*$",
    re.M,
)


class HdfcAccountParser:
    bank = "hdfc_acct"
    txn_source = "bank_account"
    gmail_query = "from:hdfcbanksmartstatement@hdfcbank.bank.in has:attachment"

    def parse(self, text: str, filename: str = "") -> ParsedStatement:
        m = re.search(r"Account Number : (\d+)", text)
        last4 = m.group(1)[-4:] if m else None

        items: list[StatementTxn] = []
        for dm, desc, withdrawal, deposit, _balance in _HDFC_ROW.findall(text):
            txn_date = datetime.strptime(dm, "%d/%m/%Y").date()
            w, d = _dec(withdrawal), _dec(deposit)
            if w:
                items.append(StatementTxn(txn_date, desc.strip()[:256], w, "debit"))
            elif d:
                items.append(StatementTxn(txn_date, desc.strip()[:256], d, "credit"))
        return _build_statement(self.bank, "HDFC Savings", last4, items)


# -- explicit credit/debit columns, irregular row layout (SBI) -------------------

# "28-05-26 UPI/DR/614881399594/ABDUSSAM/UCBA/lustretiru/UPI - 0 200.00 1766.58"
# date [narration] "-" (ref placeholder) credit debit balance. Narration is
# sometimes on its own line ABOVE a dateless "date - credit debit balance" row
# (a pdfplumber column-order artifact); such lines are buffered and attached.
_SBI_ROW = re.compile(
    r"^(\d{2}-\d{2}-\d{2})\s+(?:(.+?)\s+)?-\s+([\d.]+)\s+([\d.]+)\s+([\d,]+\.\d{2})\s*$"
)


class SbiAccountParser:
    bank = "sbi_acct"
    txn_source = "bank_account"
    gmail_query = "from:cbssbi.cas@alerts.sbi.bank.in has:attachment"

    def parse(self, text: str, filename: str = "") -> ParsedStatement:
        # The account number stands alone right under "SAVING ACCOUNT"; the
        # masked "Customer XXXXXXX####" earlier in the doc is a customer id,
        # not the account, so it must not win this match.
        m = re.search(r"SAVING ACCOUNT\s*\nX+(\d{4})", text)
        last4 = m.group(1) if m else None

        items: list[StatementTxn] = []
        buffer: list[str] = []
        for line in text.splitlines():
            line = line.strip()
            if not line:
                continue
            m2 = _SBI_ROW.match(line)
            if not m2:
                buffer.append(line)
                continue
            dm, inline_desc, credit, debit, _balance = m2.groups()
            txn_date = datetime.strptime(dm, "%d-%m-%y").date()
            desc = (inline_desc or " ".join(buffer[-2:]))[:256].strip() or "bank txn"
            buffer = []
            c, d = _dec(credit), _dec(debit)
            if d:
                items.append(StatementTxn(txn_date, desc, d, "debit"))
            elif c:
                items.append(StatementTxn(txn_date, desc, c, "credit"))
        return _build_statement(self.bank, "SBI Savings", last4, items)
