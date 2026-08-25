"""Parser for HDFC Bank SAVINGS ACCOUNT (not credit card) live UPI alerts.

This is the account the user's real day-to-day UPI spending flows through
(···7672) — separate from credit cards, which are already covered by monthly
statements + the RuPay-CC live feed (hdfc_upi.py).

Debit sample (HTML-stripped):

    Rs.110.00 is debited from your account ending 7672 towards VPA
    paytm.s1hzt87@pty (RAFEEQUE PATTATH) on 03-07-26. UPI transaction
    reference no.: 125721524336.

Credit sample (different wording — HDFC does not use symmetric templates):

    Rs.5000.00 has been successfully credited to your HDFC Bank account
    ending in 7672. Transaction Details: a. Date: 01-07-26 b. Sender:
    MEHBOOBA PARAMBIL (VPA: jessyparambil97@oksbi) c. UPI Reference No.:
    618271180521
"""

from __future__ import annotations

import re
from datetime import date, datetime
from decimal import Decimal

from app.mail.models import MailMessage
from app.services.expense_parsers.base import ParsedTxn

_DEBIT = re.compile(
    r"Rs\.?\s*([\d,]+\.\d{2})\s+is debited from your account ending\s+(\d{4})"
    r"\s+towards\s+VPA\s+(\S+)\s+\(([^)]+)\)\s+on\s+(\d{2}-\d{2}-\d{2})",
    re.I,
)
_CREDIT = re.compile(
    r"Rs\.?\s*([\d,]+\.\d{2})\s+has been successfully credited to your HDFC Bank account"
    r"\s+ending in\s+(\d{4})\..*?Date:\s*(\d{2}-\d{2}-\d{2})\s*b\.\s*Sender:\s*([^(]+)"
    r"\(VPA:\s*(\S+)\)",
    re.I | re.S,
)


def _parse_ddmmyy(raw: str) -> date | None:
    try:
        return datetime.strptime(raw, "%d-%m-%y").date()
    except ValueError:
        return None


class HdfcBankUpiParser:
    name = "hdfc_bank_upi"
    # Debits use the generic "UPI" subject (shared with the RuPay-CC alert —
    # body content, not the query, disambiguates them). Credits arrive under
    # a differently-worded "Account update" subject, so both must be matched
    # or incoming money (e.g. a returned loan) never gets fetched at all.
    gmail_query = 'from:alerts@hdfcbank.bank.in subject:(UPI OR "Account update")'

    def parse(self, email: MailMessage) -> list[ParsedTxn]:
        text = email.text

        m = _DEBIT.search(text)
        if m:
            amount = Decimal(m.group(1).replace(",", ""))
            last4 = m.group(2)
            vpa = m.group(3).rstrip(".")
            payee = m.group(4).strip()
            txn_date = _parse_ddmmyy(m.group(5)) or _received_date(email)
            return [
                ParsedTxn(
                    txn_date=txn_date,
                    amount=amount,
                    direction="debit",
                    source="upi",
                    account=f"HDFC Savings ..{last4}",
                    counterparty=f"{payee} ({vpa})",
                    raw_snippet=m.group(0)[:400],
                )
            ]

        m = _CREDIT.search(text)
        if m:
            amount = Decimal(m.group(1).replace(",", ""))
            last4 = m.group(2)
            txn_date = _parse_ddmmyy(m.group(3)) or _received_date(email)
            sender = m.group(4).strip()
            vpa = m.group(5).rstrip(".")
            return [
                ParsedTxn(
                    txn_date=txn_date,
                    amount=amount,
                    direction="credit",
                    source="upi",
                    account=f"HDFC Savings ..{last4}",
                    counterparty=f"{sender} ({vpa})",
                    raw_snippet=m.group(0)[:400],
                )
            ]

        return []


def _received_date(email: MailMessage) -> date:
    try:
        from email.utils import parsedate_to_datetime

        dt = parsedate_to_datetime(email.received)
        if dt:
            return dt.date()
    except (TypeError, ValueError):
        pass
    return date.today()
