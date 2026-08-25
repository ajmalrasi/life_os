"""Shared types for email → transaction parsers.

Each parser owns a Gmail search query and knows how to turn one matching email
into zero or more normalized transactions. Adding a new bank / card / statement
format is a matter of dropping a new parser module in this package and
registering it in ``__init__`` — nothing else in the pipeline changes.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal
from typing import Protocol, runtime_checkable

from app.mail.models import MailMessage


class ParserUnavailable(Exception):
    """A parser could not process an email because of a *transient* dependency
    failure (e.g. the LLM backend was down or out of memory) — as opposed to the
    email simply containing no transaction.

    The collector must treat this as "unknown, retry later": it must NOT advance
    its sync cursor past such an email, or the transaction it carries would be
    skipped forever once the cursor moves on. Anything the parser can decide on
    its own (not a transaction, malformed amount, etc.) should return ``[]``
    instead — those emails are genuinely done and should let the cursor advance.
    """


@dataclass
class ParsedTxn:
    """A normalized transaction, before persistence."""

    txn_date: date
    amount: Decimal  # positive magnitude
    direction: str  # "debit" | "credit"
    source: str  # "upi" | "cc_statement" | ...
    account: str | None = None
    counterparty: str | None = None
    reference: str | None = None
    category: str | None = None
    currency: str = "INR"
    raw_snippet: str | None = None
    # When the transaction happened, as a tz-aware instant. Stamped by the
    # collector (from the alert email's body time or arrival time), not by
    # individual parsers. See app/models/transaction.py::txn_at.
    txn_at: datetime | None = None
    # A parser may set a stable key itself; otherwise the collector derives one
    # (from the reference number, or a hash of the salient fields).
    dedup_key: str | None = None


@runtime_checkable
class Parser(Protocol):
    """Structural contract every parser satisfies."""

    name: str
    gmail_query: str

    def parse(self, email: MailMessage) -> list[ParsedTxn]: ...
