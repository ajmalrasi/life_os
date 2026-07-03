"""Parser for Axis Bank SAVINGS ACCOUNT live UPI debit alerts.

Sample body (HTML-stripped):

    28-06-2026
    Dear Ajmal Rasi K P,
    Here's the summary of your transaction:
    Amount Debited:
    INR 70.00
    Account Number:
    XX4402
    Date & Time:
    28-06-26, 19:17:43 IST
    Transaction Info:
    UPI/P2M/191742697112/ASMABI P C

No credit-side template has been seen yet (checked the inbox — none in the
last 45 days); this parser is debit-only until one turns up.
"""

from __future__ import annotations

import re
from datetime import date, datetime

from decimal import Decimal

from app.services.expense_parsers.base import BankEmail, ParsedTxn

_AMOUNT = re.compile(r"Amount Debited:\s*INR\s*([\d,]+\.\d{2})", re.I)
_ACCOUNT = re.compile(r"Account Number:\s*(?:XX)?(\w{4,})", re.I)
_DATE = re.compile(r"Date\s*&\s*Time:\s*(\d{2}-\d{2}-\d{2})", re.I)
_TXN_INFO = re.compile(r"Transaction Info:\s*(.+?)(?:\n|If this transaction|$)", re.I)


def _parse_ddmmyy(raw: str) -> date | None:
    try:
        return datetime.strptime(raw, "%d-%m-%y").date()
    except ValueError:
        return None


class AxisBankUpiParser:
    name = "axis_bank_upi"
    # Subject is literally "INR <amount> was debited from your A/c no. XX####"
    # — "was debited" is the stable, amount/account-independent part.
    gmail_query = 'from:alerts@axis.bank.in subject:"was debited from your A/c"'

    def parse(self, email: BankEmail) -> list[ParsedTxn]:
        text = email.text
        amount_m = _AMOUNT.search(text)
        if not amount_m:
            return []

        amount = Decimal(amount_m.group(1).replace(",", ""))

        acct_m = _ACCOUNT.search(text)
        last4 = acct_m.group(1) if acct_m else None

        date_m = _DATE.search(text)
        txn_date = (_parse_ddmmyy(date_m.group(1)) if date_m else None) or _received_date(email)

        info_m = _TXN_INFO.search(text)
        info = info_m.group(1).strip() if info_m else None
        # "UPI/P2M/<ref>/<payee>" -> pull the payee name (last field); keep
        # the full string as raw_snippet for traceability either way.
        counterparty = info.split("/")[-1].strip() if info else None

        return [
            ParsedTxn(
                txn_date=txn_date,
                amount=amount,
                direction="debit",
                source="upi",
                account=f"Axis Savings ..{last4}" if last4 else "Axis Savings",
                counterparty=counterparty,
                raw_snippet=info,
            )
        ]


def _received_date(email: BankEmail) -> date:
    try:
        from email.utils import parsedate_to_datetime

        dt = parsedate_to_datetime(email.received)
        if dt:
            return dt.date()
    except (TypeError, ValueError):
        pass
    return date.today()
