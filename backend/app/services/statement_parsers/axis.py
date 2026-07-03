"""Axis Bank credit-card statement parser (Flipkart, My Zone, ...).

Line item shape:

    30/05/2026 BBPS PAYMENT RECEIVED - DP0161... 1,255.02 Cr 0.00 Dr
    04/06/2026 FLIPKART INTERNET PVT LT,WWW.FLIPKART. DEPT STORES 41,368.00 Dr 2,068.00 Cr
"""

from __future__ import annotations

import re
from datetime import date, datetime
from decimal import Decimal

from app.services.statement_parsers.base import ParsedStatement, StatementTxn

_LINE = re.compile(r"^(\d{2}/\d{2}/\d{4})\s+(.+?)\s+([\d,]+\.\d{2})\s+(Dr|Cr)\b", re.M)

_SUMMARY = re.compile(
    r"([\d,]+\.\d{2}) Dr\s+([\d,]+\.\d{2}) Dr\s+"
    r"(\d{2}/\d{2}/\d{4}) - (\d{2}/\d{2}/\d{4})\s+(\d{2}/\d{2}/\d{4})\s+(\d{2}/\d{2}/\d{4})"
)


def _d(raw: str) -> date | None:
    try:
        return datetime.strptime(raw, "%d/%m/%Y").date()
    except ValueError:
        return None


class AxisStatementParser:
    bank = "axis"
    gmail_query = "from:cc.statements@axis.bank.in has:attachment"

    def parse(self, text: str, filename: str = "") -> ParsedStatement:
        m = re.search(r"^(.*?) Credit Card Statement", text)
        label = ("Axis " + m.group(1).replace("Axis Bank", "").strip()) if m else "Axis Card"

        m = re.search(r"\d{6}\*+(\d{4})", text)
        last4 = m.group(1) if m else None

        total_due = min_due = None
        period_start = period_end = due_date = stmt_date = None
        m = _SUMMARY.search(text)
        if m:
            total_due = Decimal(m.group(1).replace(",", ""))
            min_due = Decimal(m.group(2).replace(",", ""))
            period_start, period_end = _d(m.group(3)), _d(m.group(4))
            due_date, stmt_date = _d(m.group(5)), _d(m.group(6))

        # Previous Balance - Payments - Credits + Purchase + Cash + Other = Total
        m = re.search(
            r"=Total Payment Due.*?\n[\d,]+\.\d{2} (?:Dr|Cr)?\s*[\d,]+\.\d{2}\s+"
            r"[\d,]+\.\d{2}\s+([\d,]+\.\d{2})",
            text,
            re.S,
        )
        total_spend = Decimal(m.group(1).replace(",", "")) if m else None

        items: list[StatementTxn] = []
        for dm, desc, amt, drcr in _LINE.findall(text):
            txn_date = _d(dm)
            if txn_date is None:
                continue
            items.append(
                StatementTxn(
                    txn_date=txn_date,
                    description=desc.strip(),
                    amount=Decimal(amt.replace(",", "")),
                    direction="credit" if drcr == "Cr" else "debit",
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
