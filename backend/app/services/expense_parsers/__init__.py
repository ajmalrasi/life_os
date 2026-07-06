"""Registry of email → transaction parsers.

To support a new source (another bank's UPI alert, a credit-card statement PDF,
etc.), add a parser module here and append an instance to ``_REGISTRY``.
"""

from __future__ import annotations

from app.services.expense_parsers.axis_bank_upi import AxisBankUpiParser
from app.services.expense_parsers.base import BankEmail, ParsedTxn, Parser, ParserUnavailable
from app.services.expense_parsers.hdfc_bank_upi import HdfcBankUpiParser
from app.services.expense_parsers.hdfc_diners_live import HdfcDinersLiveParser
from app.services.expense_parsers.hdfc_upi import HdfcUpiParser
from app.services.expense_parsers.llm_bank_parser import LlmBankParser

_REGISTRY: list[Parser] = [
    HdfcUpiParser(),
    HdfcDinersLiveParser(),
    HdfcBankUpiParser(),
    AxisBankUpiParser(),
    # Catch-all: broader query (bank domain only, no subject regex), so it
    # only ever sees emails none of the parsers above already claimed (the
    # collector dedupes by Gmail message_id before any parser runs).
    LlmBankParser(),
]


def get_parsers() -> list[Parser]:
    """Return all registered parsers."""
    return list(_REGISTRY)


__all__ = ["BankEmail", "ParsedTxn", "Parser", "ParserUnavailable", "get_parsers"]
