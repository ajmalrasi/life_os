"""Registry of credit-card statement parsers, keyed by bank.

Add a bank = add a parser module and register it here.
"""

from __future__ import annotations

from app.services.statement_parsers.axis import AxisStatementParser
from app.services.statement_parsers.bank_accounts import (
    AxisAccountParser,
    HdfcAccountParser,
    IciciAccountParser,
    SbiAccountParser,
)
from app.services.statement_parsers.base import ParsedStatement, StatementTxn
from app.services.statement_parsers.hdfc import HdfcStatementParser
from app.services.statement_parsers.icici import IciciStatementParser
from app.services.statement_parsers.sbi import SbiStatementParser

_REGISTRY = [
    HdfcStatementParser(),
    AxisStatementParser(),
    IciciStatementParser(),
    SbiStatementParser(),
    # Savings accounts (line items land as source='bank_account').
    IciciAccountParser(),
    AxisAccountParser(),
    HdfcAccountParser(),
    SbiAccountParser(),
]


def get_statement_parsers():
    return list(_REGISTRY)


__all__ = ["ParsedStatement", "StatementTxn", "get_statement_parsers"]
