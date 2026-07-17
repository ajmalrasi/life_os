"""Select the small subset of statement lines that represent EMI charges.

Ordinary statement lines are ignored because live alerts are the transaction
source of truth. Explicit installment/interest/fee narrations are deterministic;
merchant-style ``EMI <merchant>`` lines are reviewed in one batched local-model
call because banks use that wording for several different EMI layouts.
"""

from __future__ import annotations

import json
import logging
import re

from app.services.openclaw import OpenClawClient, OpenClawUnavailable
from app.services.statement_parsers.base import StatementTxn

logger = logging.getLogger("lifeos.statement_emi")

_EXPLICIT_EMI = re.compile(
    r"(?:"
    r"\bEMI\s+(?:PRINCIPAL|INTEREST|PROCESSING\s+FEE)\b|"
    r"\bINTEREST\s+ON\s+EMI\b|"
    r"\bMER\s+EMI\b.*\bINT\s+NBR\b|"
    r"^EMI\s+\d+\s+CHQ\b|"
    r"\b(?:LOAN\s+EMI|EMI\s+INSTAL+MENT|EMI\s+INSTALLMENT|FLEXIPAY)\b"
    r")",
    re.IGNORECASE,
)
_POSSIBLE_EMI = re.compile(
    r"(?:\bEMI\b|\bINSTAL+MENT\b|\bINSTALLMENT\b|\bEASY[ -]?EMI\b|\bSMART[ -]?EMI\b)",
    re.IGNORECASE,
)
_CONVERSION_CREDIT = re.compile(r"\b(?:TRANSACTION\s+)?CONVERSION\s+INTO\s+EMI\b", re.IGNORECASE)

_REVIEW_SCHEMA = {
    "type": "object",
    "properties": {
        "keep_ids": {"type": "string"},
        "reason": {"type": "string"},
    },
    "required": ["keep_ids", "reason"],
    "additionalProperties": False,
}
_REVIEW_PROMPT = (
    "These are debit line items from one Indian bank statement. Live card/bank "
    "alerts already capture ordinary purchases. Select only rows that are EMI "
    "installment components or EMI-specific charges that live alerts commonly "
    "miss. Do not select an original purchase merely because it mentions EMI, "
    "and do not select conversion/reversal credits. If uncertain, omit it. "
    'Return keep_ids as a comma-separated string of the numeric row ids (for example "1,3"), '
    'or "" when none should be kept.'
)


def is_explicit_emi(description: str) -> bool:
    """True only for narration formats that unambiguously describe EMI costs."""
    return bool(_EXPLICIT_EMI.search(description or ""))


def is_possible_emi(description: str) -> bool:
    """Word-boundary check; avoids false positives like chemist/remittance."""
    return bool(_POSSIBLE_EMI.search(description or ""))


def select_emi_items(
    items: list[StatementTxn], client: OpenClawClient | None = None
) -> list[StatementTxn]:
    """Return statement debit rows safe to add to the transaction feed.

    Explicit patterns are deterministic. Ambiguous EMI-looking rows are sent
    to the local model once as a batch. Model failure, malformed output, or an
    omitted id means "do not import" so uncertainty cannot inflate spending.
    """
    selected: list[StatementTxn] = []
    ambiguous: list[tuple[int, StatementTxn]] = []

    for row_id, item in enumerate(items, start=1):
        description = item.description or ""
        if item.direction != "debit" or _CONVERSION_CREDIT.search(description):
            continue
        if is_explicit_emi(description):
            selected.append(item)
        elif is_possible_emi(description):
            ambiguous.append((row_id, item))

    if not ambiguous:
        return selected

    lines = "\n".join(
        f"{row_id}|{item.txn_date.isoformat()}|{item.amount}|{item.description[:180]}"
        for row_id, item in ambiguous[:25]
    )
    messages = [
        {"role": "system", "content": _REVIEW_PROMPT},
        {"role": "user", "content": "STATEMENT ROWS:\n" + lines},
    ]
    try:
        raw = (client or OpenClawClient()).chat_structured(
            messages, schema=_REVIEW_SCHEMA, schema_name="statement_emi_review", temperature=0.0
        )
        data = json.loads(raw)
        keep_ids = {int(value) for value in re.findall(r"\d+", str(data.get("keep_ids", "")))}
    except (OpenClawUnavailable, ValueError, TypeError) as exc:
        logger.warning("ambiguous statement EMI rows skipped: %s", exc)
        return selected

    selected.extend(item for row_id, item in ambiguous if row_id in keep_ids)
    return selected
