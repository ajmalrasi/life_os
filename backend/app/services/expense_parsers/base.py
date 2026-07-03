"""Shared types for email → transaction parsers.

Each parser owns a Gmail search query and knows how to turn one matching email
into zero or more normalized transactions. Adding a new bank / card / statement
format is a matter of dropping a new parser module in this package and
registering it in ``__init__`` — nothing else in the pipeline changes.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from typing import Protocol, runtime_checkable


@dataclass(frozen=True)
class BankEmail:
    """A fetched email, normalized for parsers (HTML already stripped to text)."""

    message_id: str  # Gmail message id (provenance + idempotency)
    subject: str
    from_addr: str
    received: str  # raw RFC date header, used only as a date fallback
    text: str  # HTML-stripped, whitespace-collapsed plaintext body
    raw_body: str  # original body, in case a parser needs the markup
    internal_date_ms: int = 0  # Gmail internalDate (epoch ms); drives the sync cursor


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
    # A parser may set a stable key itself; otherwise the collector derives one
    # (from the reference number, or a hash of the salient fields).
    dedup_key: str | None = None


@runtime_checkable
class Parser(Protocol):
    """Structural contract every parser satisfies."""

    name: str
    gmail_query: str

    def parse(self, email: BankEmail) -> list[ParsedTxn]: ...
