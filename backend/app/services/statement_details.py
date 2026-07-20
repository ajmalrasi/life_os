"""Classification and rollups for statement-only explanatory rows."""

from __future__ import annotations

import re
from collections import defaultdict
from decimal import Decimal

from app.models.card_statement import CardStatementItem
from app.services.statement_parsers.base import StatementTxn

DETAILS_PARSER_VERSION = 1

_PAYMENT = re.compile(r"payment received|cc payment|card payment|bbps payment|autopay", re.I)
_EMI = re.compile(r"\bemi\b|\binstal+ment\b|\binstallment\b|\bflexipay\b", re.I)
_FEE_INTEREST = re.compile(
    r"\binterest\b|finance charge|processing fee|late fee|annual fee|gst|igst|surcharge|markup",
    re.I,
)
_CASH = re.compile(r"cash advance|atm cash|cash withdrawal", re.I)


def classify_statement_item(item: StatementTxn) -> str:
    """Assign a display-only type; this is not a spend category."""
    text = item.description or ""
    if item.direction == "credit":
        return "payment" if _PAYMENT.search(text) else "credit"
    if _EMI.search(text):
        return "emi"
    if _FEE_INTEREST.search(text):
        return "fee_interest"
    if _CASH.search(text):
        return "cash_advance"
    return "purchase"


def statement_breakdown(
    items: list[CardStatementItem], total_due: Decimal | None = None
) -> list[dict]:
    """Produce activity totals and reconcile them to the bank's total due.

    Statement line items alone do not necessarily explain the bill: a card can
    carry a previous balance or bank-level adjustments that are present only in
    the summary. Expose that residual honestly instead of presenting categories
    that appear to add up when they do not.
    """
    totals: dict[str, Decimal] = defaultdict(lambda: Decimal(0))
    counts: dict[str, int] = defaultdict(int)
    for item in items:
        if item.direction == "credit":
            key = "payments_credits"
            totals[key] -= item.amount
        elif item.item_type == "emi":
            key = "emi"
            totals[key] += item.amount
        elif item.item_type == "fee_interest":
            key = "fees_interest"
            totals[key] += item.amount
        else:
            key = "new_charges"
            totals[key] += item.amount
        counts[key] += 1

    labels = {
        "new_charges": "New charges",
        "emi": "EMI",
        "fees_interest": "Interest & fees",
        "payments_credits": "Payments & credits",
    }
    order = ("new_charges", "emi", "fees_interest", "payments_credits")
    breakdown = [
        {"key": key, "label": labels[key], "total": totals[key], "count": counts[key]}
        for key in order
        if counts[key]
    ]
    if total_due is not None:
        activity_total = sum((part["total"] for part in breakdown), Decimal(0))
        residual = total_due - activity_total
        if abs(residual) >= Decimal("0.01"):
            breakdown.insert(
                0,
                {
                    "key": "previous_balance_adjustments",
                    "label": "Previous balance & adjustments",
                    "total": residual,
                    "count": 0,
                },
            )
    return breakdown
