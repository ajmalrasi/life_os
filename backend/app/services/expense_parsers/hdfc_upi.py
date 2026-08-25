"""Parser for HDFC Bank RuPay Credit Card UPI transaction alerts.

Sample body (HTML-stripped):

    ... Transaction Details: Rs.190.00 has been debited from your RuPay Credit
    Card (ending 5473) Paid to q186411044@ybl Date: 02-07-26 UPI Transaction
    Reference Number: 125669193205 ...
"""

from __future__ import annotations

import re
from datetime import date, datetime
from decimal import Decimal

from app.mail.models import MailMessage
from app.services.expense_parsers.base import ParsedTxn

_AMOUNT = re.compile(r"Rs\.?\s*([\d,]+\.\d{2})\s+has been\s+(debited|credited)", re.I)
_CARD = re.compile(r"Credit Card\s*\(ending\s*(\d{4})\)", re.I)
_PAID_TO = re.compile(r"Paid to\s+([^\s]+)", re.I)
_DATE = re.compile(r"Date:\s*(\d{2}-\d{2}-\d{2})")
_REF = re.compile(r"Reference Number:\s*(\d+)", re.I)


def _parse_ddmmyy(raw: str) -> date | None:
    try:
        return datetime.strptime(raw, "%d-%m-%y").date()
    except ValueError:
        return None


class HdfcUpiParser:
    name = "hdfc_upi"
    # Sender + subject keyword keep this to the RuPay-CC UPI alert only.
    gmail_query = "from:alerts@hdfcbank.bank.in subject:UPI"

    def parse(self, email: MailMessage) -> list[ParsedTxn]:
        text = email.text
        amount_m = _AMOUNT.search(text)
        if not amount_m:
            return []

        amount = Decimal(amount_m.group(1).replace(",", ""))
        direction = "debit" if amount_m.group(2).lower() == "debited" else "credit"

        card_m = _CARD.search(text)
        account = f"HDFC RuPay CC ..{card_m.group(1)}" if card_m else "HDFC RuPay CC"

        paid_m = _PAID_TO.search(text)
        counterparty = paid_m.group(1).rstrip(".") if paid_m else None

        ref_m = _REF.search(text)
        reference = ref_m.group(1) if ref_m else None

        date_m = _DATE.search(text)
        txn_date = (_parse_ddmmyy(date_m.group(1)) if date_m else None) or _received_date(email)

        return [
            ParsedTxn(
                txn_date=txn_date,
                amount=amount,
                direction=direction,
                source="upi",
                account=account,
                counterparty=counterparty,
                reference=reference,
                raw_snippet=_snippet(text),
            )
        ]


def _received_date(email: MailMessage) -> date:
    # RFC 2822 header, e.g. "Thu, 02 Jul 2026 23:51:17 +0530"
    try:
        from email.utils import parsedate_to_datetime

        dt = parsedate_to_datetime(email.received)
        if dt:
            return dt.date()
    except (TypeError, ValueError):
        pass
    return date.today()


def _snippet(text: str, limit: int = 300) -> str:
    marker = "Transaction Details"
    idx = text.find(marker)
    chunk = text[idx:] if idx >= 0 else text
    return chunk[:limit].strip()
