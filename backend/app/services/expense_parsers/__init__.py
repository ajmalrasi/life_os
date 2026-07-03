"""Registry of email → transaction parsers.

To support a new source (another bank's UPI alert, a credit-card statement PDF,
etc.), add a parser module here and append an instance to ``_REGISTRY``.
"""

from __future__ import annotations

from app.services.expense_parsers.base import BankEmail, ParsedTxn, Parser
from app.services.expense_parsers.hdfc_upi import HdfcUpiParser

_REGISTRY: list[Parser] = [
    HdfcUpiParser(),
]


def get_parsers() -> list[Parser]:
    """Return all registered parsers."""
    return list(_REGISTRY)


__all__ = ["BankEmail", "ParsedTxn", "Parser", "get_parsers"]
