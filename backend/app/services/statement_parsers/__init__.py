"""Registry of credit-card statement parsers, keyed by bank.

Add a bank = add a parser module and register it here.
"""

from __future__ import annotations

from app.services.statement_parsers.axis import AxisStatementParser
from app.services.statement_parsers.base import ParsedStatement, StatementTxn
from app.services.statement_parsers.hdfc import HdfcStatementParser
from app.services.statement_parsers.icici import IciciStatementParser
from app.services.statement_parsers.sbi import SbiStatementParser

# Only credit-card statement PDFs belong in the active collector. Savings/current
# account e-statements must not be searched, downloaded, parsed, or stored.
_REGISTRY = [
    HdfcStatementParser(),
    AxisStatementParser(),
    IciciStatementParser(),
    SbiStatementParser(),
]


def get_statement_parsers():
    return list(_REGISTRY)


__all__ = ["ParsedStatement", "StatementTxn", "get_statement_parsers"]
