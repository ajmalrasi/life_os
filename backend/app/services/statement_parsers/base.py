"""Shared types for credit-card statement PDF parsers.

Each bank parser turns the extracted text of one decrypted statement into a
``ParsedStatement`` (summary + line items). Card type/label comes from the
attachment filename where the bank uses a generic subject (e.g. ICICI).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, time
from decimal import Decimal


@dataclass
class StatementTxn:
    txn_date: date
    description: str
    amount: Decimal  # positive magnitude
    direction: str  # "debit" | "credit"
    txn_time: time | None = None  # local card-statement time when the PDF provides it


@dataclass
class ParsedStatement:
    bank: str  # "hdfc" | "axis" | "icici" | "sbi"
    card_label: str  # e.g. "HDFC Diners Privilege"
    card_last4: str | None
    statement_date: date | None
    period_start: date | None
    period_end: date | None
    total_due: Decimal | None
    min_due: Decimal | None
    due_date: date | None
    total_spend: Decimal | None  # purchases/charges this cycle
    line_items: list[StatementTxn] = field(default_factory=list)
