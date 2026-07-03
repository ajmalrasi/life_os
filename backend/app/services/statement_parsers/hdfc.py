"""HDFC credit-card statement parser (Diners Privilege, Tata Neu Plus, ...).

Line item shapes (text extracted via pdfplumber):

    20/05/2026| 12:29 UPI-SHIHAB K P C 156.00 l                      (debit)
    24/05/2026| 15:37 EVERSUB INDIA PRIVATELCALICUT + 12 C 576.00 l  (debit, +12 points)
    30/05/2026| 09:34 BPPY CC PAYMENT DP0161... + C 17,843.00 l      (credit: bare "+")
    08/06/2026 | 15:30 CURSOR, AI POWERED IDE83... USD 23.60 + 44 C 2,259.96 l
"""

from __future__ import annotations

import re
from datetime import date, datetime
from decimal import Decimal

from app.services.statement_parsers.base import ParsedStatement, StatementTxn

_LINE = re.compile(
    r"^(\d{2}/\d{2}/\d{4})\s*\|\s*\d{2}:\d{2}\s+(.+?)\s+C\s?([\d,]+\.\d{2})\s*l?\s*$",
    re.M,
)
_POINTS_TAIL = re.compile(r"\s*\+\s*\d+\s*$")


def _d(raw: str, fmt: str) -> date | None:
    try:
        return datetime.strptime(raw, fmt).date()
    except ValueError:
        return None


class HdfcStatementParser:
    bank = "hdfc"
    gmail_query = "from:Emailstatements.cards@hdfcbank.bank.in has:attachment"

    def parse(self, text: str, filename: str = "") -> ParsedStatement:
        m = re.search(r"^(.*?Credit Card) Statement", text)
        label = ("HDFC " + m.group(1).replace("HDFC Bank", "").strip()) if m else "HDFC Card"

        m = re.search(r"Credit Card No\. [\dX]+?(\d{4})\b", text)
        last4 = m.group(1) if m else None

        m = re.search(r"Statement Date (\d{2} \w{3}, \d{4})", text)
        stmt_date = _d(m.group(1), "%d %b, %Y") if m else None

        period_start = period_end = None
        m = re.search(r"Billing Period (\d{2} \w{3}, \d{4}) - (\d{2} \w{3}, \d{4})", text)
        if m:
            period_start = _d(m.group(1), "%d %b, %Y")
            period_end = _d(m.group(2), "%d %b, %Y")

        m = re.search(r"TOTAL AMOUNT DUE.*?C([\d,]+\.\d{2})", text, re.S)
        total_due = Decimal(m.group(1).replace(",", "")) if m else None

        min_due = due_date = None
        m = re.search(r"MINIMUM DUE DUE DATE\s*\nC([\d,]+\.\d{2})\s+(\d{2} \w{3}, \d{4})", text)
        if m:
            min_due = Decimal(m.group(1).replace(",", ""))
            due_date = _d(m.group(2), "%d %b, %Y")

        m = re.search(r"\+ C([\d,]+\.\d{2}) \+ C[\d,]+\.\d{2} =", text)
        total_spend = Decimal(m.group(1).replace(",", "")) if m else None

        items: list[StatementTxn] = []
        for dm, desc, amt in _LINE.findall(text):
            desc = desc.strip()
            direction = "debit"
            if desc.endswith("+"):  # bare "+" before C -> payment/credit
                direction = "credit"
                desc = desc[:-1].strip()
            else:
                desc = _POINTS_TAIL.sub("", desc)  # "+ NN" tail is reward points
            txn_date = _d(dm, "%d/%m/%Y")
            if txn_date is None:
                continue
            items.append(
                StatementTxn(
                    txn_date=txn_date,
                    description=desc,
                    amount=Decimal(amt.replace(",", "")),
                    direction=direction,
                )
            )

        return ParsedStatement(
            bank=self.bank,
            card_label=label,
            card_last4=last4,
            statement_date=stmt_date,
            period_start=period_start,
            period_end=period_end,
            total_due=total_due,
            min_due=min_due,
            due_date=due_date,
            total_spend=total_spend,
            line_items=items,
        )
