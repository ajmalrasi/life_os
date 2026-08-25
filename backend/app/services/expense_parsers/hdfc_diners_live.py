"""Parser for HDFC Bank Diners Privilege Credit Card live transaction alerts.

Unlike the RuPay-CC UPI alerts (``hdfc_upi.py``), this template covers EVERY
transaction type on the card — POS, online, UPI, everything — not just UPI.

Sample body (HTML-stripped):

    ... We would like to inform you that Rs. 5156.50 has been debited from
    your HDFC Bank Credit Card ending 6116 towards WWW MOBIKWIK COM on 29 Jun,
    2026 at 14:41:02 . ...
"""

from __future__ import annotations

import re
from datetime import date, datetime
from decimal import Decimal

from app.mail.models import MailMessage
from app.services.expense_parsers.base import ParsedTxn

_TXN = re.compile(
    r"Rs\.?\s*([\d,]+\.\d{2})\s+has been\s+(debited|credited)\s+from your HDFC Bank Credit Card"
    r"\s+ending\s+(\d{4})\s+towards\s+(.+?)\s+on\s+(\d{1,2}\s+\w{3},\s+\d{4})",
    re.I | re.S,
)


class HdfcDinersLiveParser:
    name = "hdfc_diners_live"
    # Distinguishes from the RuPay/UPI-only template (hdfc_upi.py), which uses
    # a different subject line ("UPI txn"). This one arrives per-transaction
    # for every purchase type on the Diners card.
    gmail_query = 'from:alerts@hdfcbank.bank.in subject:"A payment was made using your Credit Card"'

    def parse(self, email: MailMessage) -> list[ParsedTxn]:
        m = _TXN.search(email.text)
        if not m:
            return []

        amount = Decimal(m.group(1).replace(",", ""))
        direction = "debit" if m.group(2).lower() == "debited" else "credit"
        last4 = m.group(3)
        merchant = " ".join(m.group(4).split())
        txn_date = _parse_date(m.group(5)) or _received_date(email)

        return [
            ParsedTxn(
                txn_date=txn_date,
                amount=amount,
                direction=direction,
                source="hdfc_card_live",
                account=f"HDFC Diners Privilege CC ..{last4}",
                counterparty=merchant,
                raw_snippet=m.group(0)[:400],
            )
        ]


def _parse_date(raw: str) -> date | None:
    try:
        return datetime.strptime(raw.strip(), "%d %b, %Y").date()
    except ValueError:
        return None


def _received_date(email: MailMessage) -> date:
    try:
        from email.utils import parsedate_to_datetime

        dt = parsedate_to_datetime(email.received)
        if dt:
            return dt.date()
    except (TypeError, ValueError):
        pass
    return date.today()
