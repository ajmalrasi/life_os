"""Parse confirmed Axis Amex and RBL credit card purchase alerts."""

from __future__ import annotations

import re
from datetime import datetime
from decimal import Decimal
from email.utils import parseaddr
from zoneinfo import ZoneInfo

from app.mail.models import MailMessage
from app.services.expense_parsers.base import ParsedTxn

_AMOUNT = r"(?P<amount>(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{2,3})+)(?:\.[0-9]{1,2})?)"
_DATE = r"(?P<date>[0-9]{2}-[0-9]{2}-[0-9]{4})"
_AXIS = re.compile(
    r"summary of your Axis Bank Credit Card Transaction:\s*"
    r"Transaction Amount:\s*INR\s*" + _AMOUNT +
    r"\s+Merchant Name:\s*(?P<merchant>.+?)\s+"
    r"Axis Bank Credit Card No\.\s*XX2545\s+"
    r"Date & Time:\s*" + _DATE + r",\s*(?P<time>[0-9:]{8})\s+IST\b",
    re.I | re.S,
)
_RBL = re.compile(
    r"\bCard swipe\s+INR\s*" + _AMOUNT + r"\s+spent at\s+(?P<merchant>.+?)"
    r"\s+on RBL Bank credit card\s*\((?P<last4>[0-9]{4})\)\s+on\s+" + _DATE + r"\b",
    re.I | re.S,
)


class AxisAmexPurchaseParser:
    name = "axis_amex_purchase"
    gmail_query = 'from:alerts@axis.bank.in subject:"spent on credit card no. XX2545"'

    def parse(self, email: MailMessage) -> list[ParsedTxn]:
        if parseaddr(email.from_addr)[1].casefold() != "alerts@axis.bank.in":
            return []
        if not re.fullmatch(r"INR .+ spent on credit card no\. XX2545", email.subject.strip(), re.I):
            return []
        match = _AXIS.search(email.text)
        if not match:
            return []
        return _purchase(match, "Axis American Express ..2545")


class RblCardPurchaseParser:
    name = "rbl_card_purchase"
    gmail_query = 'from:rblalerts@rbl.bank.in subject:"Your RBL Bank Credit Card has just been swiped"'

    def parse(self, email: MailMessage) -> list[ParsedTxn]:
        if parseaddr(email.from_addr)[1].casefold() != "rblalerts@rbl.bank.in":
            return []
        if email.subject.strip().casefold() != "alert: your rbl bank credit card has just been swiped":
            return []
        match = _RBL.search(email.text)
        if not match:
            return []
        return _purchase(match, f"RBL Bank Credit Card ..{match['last4']}")


def _purchase(match: re.Match[str], account: str) -> list[ParsedTxn]:
    amount = Decimal(match["amount"].replace(",", ""))
    merchant = " ".join(match["merchant"].split())
    if amount <= 0 or not merchant:
        return []
    clock = match.groupdict().get("time")
    try:
        if clock:
            happened_at = datetime.strptime(f"{match['date']} {clock}", "%d-%m-%Y %H:%M:%S")
            happened_at = happened_at.replace(tzinfo=ZoneInfo("Asia/Kolkata"))
            txn_date = happened_at.date()
        else:
            happened_at = None
            txn_date = datetime.strptime(match["date"], "%d-%m-%Y").date()
    except ValueError:
        return []
    return [ParsedTxn(
        txn_date=txn_date,
        amount=amount,
        direction="debit",
        source="card_live",
        account=account,
        counterparty=merchant,
        raw_snippet=match[0][:400],
        txn_at=happened_at,
    )]
