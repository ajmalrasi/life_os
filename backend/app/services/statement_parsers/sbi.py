"""SBI Card statement parser (BPCL Octane, ...).

Line item shape:

    24 May 26 CITY PETROLEUM KOTTAKKAL IN 1,010.00 D
    30 May 26 PAYMENT RECEIVED 000DP0161... 14,658.00 C
"""

from __future__ import annotations

import re
from datetime import date, datetime
from decimal import Decimal

from app.services.statement_parsers.base import ParsedStatement, StatementTxn

_LINE = re.compile(r"^(\d{2} \w{3} \d{2})\s+(.+?)\s+([\d,]+\.\d{2})\s+([CD])\s*$", re.M)


def _d(raw: str, fmt: str) -> date | None:
    try:
        return datetime.strptime(raw, fmt).date()
    except ValueError:
        return None


class SbiStatementParser:
    bank = "sbi"
    gmail_query = "from:Statements@sbicard.com has:attachment"

    def parse(self, text: str, filename: str = "") -> ParsedStatement:
        m = re.search(r"Your (.+?) Monthly Statement", text)
        label = f"SBI {m.group(1)}" if m else "SBI Card"

        m = re.search(r"XXXX XXXX XXXX X*(\d{2,4})", text)
        last4 = m.group(1) if m else None

        m = re.search(r"Total Amount Due \( ` \)\s*\n([\d,]+\.\d{2})", text)
        total_due = Decimal(m.group(1).replace(",", "")) if m else None

        m = re.search(r"Minimum Amount Due\( ` \)[\s\S]{0,120}?([\d,]+\.\d{2})", text)
        min_due = Decimal(m.group(1).replace(",", "")) if m else None

        m = re.search(r"Statement Date\s*\n?.*?(\d{2} \w{3} \d{4})", text)
        stmt_date = _d(m.group(1), "%d %b %Y") if m else None

        m = re.search(r"Payment Due Date[\s\S]{0,120}?(\d{2} \w{3} \d{4})", text)
        due_date = _d(m.group(1), "%d %b %Y") if m else None

        period_start = period_end = None
        m = re.search(r"Statement Period: (\d{2} \w{3} \d{2}) to (\d{2} \w{3} \d{2})", text)
        if m:
            period_start = _d(m.group(1), "%d %b %y")
            period_end = _d(m.group(2), "%d %b %y")

        # ACCOUNT SUMMARY row: prev, payments/credits, purchases, fees, outstanding
        m = re.search(
            r"([\d,]+\.\d{2})\s+([\d,]+\.\d{2})\s+([\d,]+\.\d{2})\s+([\d,]+\.\d{2})\s+([\d,]+\.\d{2})",
            text,
        )
        total_spend = Decimal(m.group(3).replace(",", "")) if m else None

        items: list[StatementTxn] = []
        for dm, desc, amt, cd in _LINE.findall(text):
            txn_date = _d(dm, "%d %b %y")
            if txn_date is None:
                continue
            items.append(
                StatementTxn(
                    txn_date=txn_date,
                    description=desc.strip(),
                    amount=Decimal(amt.replace(",", "")),
                    direction="credit" if cd == "C" else "debit",
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
