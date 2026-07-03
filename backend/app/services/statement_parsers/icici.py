"""ICICI Bank credit-card statement parser (Coral, Amazon Pay, HPCL, ...).

ICICI uses one generic email subject for every card; the card type comes from
the attachment filename, e.g. ``6528XXXXXXXX3006_925424_Retail_Coral_A.pdf``.

Line item shape (serial number then optional points then amount, CR = credit):

    19/05/2026 13442059612 UPI-613978022793-AM AUTOM OTIVE 377 18,880.00
    30/05/2026 13504819520 BBPS Payment received 0 17,725.98 CR

Note: some ICICI PDFs render header words with doubled glyphs
("PPAAYYMMEENNTT DDUUEE DDAATTEE"), so header regexes tolerate both forms.
"""

from __future__ import annotations

import re
from datetime import date, datetime
from decimal import Decimal

from app.services.statement_parsers.base import ParsedStatement, StatementTxn

_LINE = re.compile(
    r"^(\d{2}/\d{2}/\d{4})\s+\d{8,}\s+(.+?)\s+(-?\d+)\s+([\d,]+\.\d{2})(\s+CR)?\s*$",
    re.M,
)


def _d(raw: str) -> date | None:
    for fmt in ("%B %d, %Y", "%b %d, %Y"):
        try:
            return datetime.strptime(raw.strip(), fmt).date()
        except ValueError:
            continue
    return None


def _dedouble(s: str) -> str:
    """Collapse doubled characters: 'JJuunnee 1166' -> 'June 16' (best effort)."""
    if len(s) < 4:
        return s
    half = len(s) // 2
    if len(s) % 2 == 0 and all(s[i] == s[i + 1] for i in range(0, len(s), 2)):
        return s[::2]
    return s


class IciciStatementParser:
    bank = "icici"
    gmail_query = "from:credit_cards@icici.bank.in subject:statement has:attachment"

    def parse(self, text: str, filename: str = "") -> ParsedStatement:
        m = re.search(r"Retail_(\w+)_A", filename)
        label = f"ICICI {m.group(1)}" if m else "ICICI Card"

        m = re.search(r"\d{4}X{4,}(\d{4})", text)
        last4 = m.group(1) if m else None

        m = re.search(r"Total Amount due\s*\n`?([\d,]+\.\d{2})", text, re.I)
        total_due = Decimal(m.group(1).replace(",", "")) if m else None

        m = re.search(r"Minimum Amount due[^\n]*\n`?([\d,]+\.\d{2})", text, re.I)
        min_due = Decimal(m.group(1).replace(",", "")) if m else None

        stmt_date = due_date = None
        for pat, target in (
            (r"S?STATEMENT DATE|SSTTAATTEEMMEENNTT DDAATTEE", "stmt"),
            (r"PAYMENT DUE DATE|PPAAYYMMEENNTT DDUUEE DDAATTEE", "due"),
        ):
            m = re.search(rf"(?:{pat})\s*\n([^\n]+)", text)
            if m:
                parsed = _d(_dedouble(m.group(1).strip()))
                if target == "stmt":
                    stmt_date = parsed
                else:
                    due_date = parsed

        period_start = period_end = None
        m = re.search(r"Statement period : (\w+ \d{1,2}, \d{4}) to (\w+ \d{1,2}, \d{4})", text)
        if m:
            period_start, period_end = _d(m.group(1)), _d(m.group(2))

        m = re.search(r"Previous Balance Purchases.*?\n`[\d,]+\.\d{2} `([\d,]+\.\d{2})", text, re.S)
        total_spend = Decimal(m.group(1).replace(",", "")) if m else None

        items: list[StatementTxn] = []
        for dm, desc, _points, amt, cr in _LINE.findall(text):
            try:
                txn_date = datetime.strptime(dm, "%d/%m/%Y").date()
            except ValueError:
                continue
            items.append(
                StatementTxn(
                    txn_date=txn_date,
                    description=desc.strip(),
                    amount=Decimal(amt.replace(",", "")),
                    direction="credit" if cr else "debit",
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
