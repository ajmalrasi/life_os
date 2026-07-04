"""LLM-based fallback categorization for payees the keyword rules miss.

The deterministic categorizer (``app.api.routes.statements._CATEGORY_RULES``
plus user-authored ``payee_labels``) is fast and predictable, but plain
substring matching produces false positives on ordinary names (e.g. "RAFEEQUE"
contains "fee", so it was miscategorized as ``emi_fees``) and leaves anything
without a matching keyword in the catch-all "other" bucket.

This module asks OpenClaw to categorize a payee instead, constrained to the
same fixed category list via JSON schema (no free-text parsing, no category
hallucination). It deliberately excludes the categories in
``services.reconcile.EXCLUDED_FROM_SPEND`` — those change whether a
transaction counts as spend at all, so they must only ever be assigned by the
narrow, auditable rules in ``reconcile.py``, never an LLM guess.

Results are written into ``payee_labels`` — the same table the user edits by
hand from the Money tab — so a suggestion is reviewed/overridden the same way
a manual label is. High-confidence suggestions are activated immediately;
medium/low confidence ones are inserted inactive (a review queue: they don't
affect categorization until a human flips them on).
"""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass

from sqlalchemy.orm import Session

from app.config import get_settings
from app.models.transaction import Transaction
from app.repositories.payee_label_repo import PayeeLabelRepository
from app.services.openclaw import OpenClawClient, OpenClawUnavailable

logger = logging.getLogger("lifeos.payee_classifier")
settings = get_settings()

_CLASSIFY_TIMEOUT_SECONDS = 30.0
# Caps LLM calls per sync run so a large backfill can't stall the request for
# minutes; any payees beyond the cap just get picked up on the next sync.
_MAX_PAYEES_PER_RUN = 20

ALLOWED_CATEGORIES = [
    "fuel", "wallet_load", "food", "groceries", "shopping", "subscriptions",
    "investments", "entertainment", "emi_fees", "travel", "legal_fees", "other",
]

_SCHEMA = {
    "type": "object",
    "properties": {
        "category": {"type": "string", "enum": ALLOWED_CATEGORIES},
        "confidence": {"type": "string", "enum": ["high", "medium", "low"]},
        "reason": {"type": "string"},
    },
    "required": ["category", "confidence", "reason"],
    "additionalProperties": False,
}

_SYSTEM_PROMPT = (
    "You categorize a bank/UPI transaction's spend category from its payee "
    "name. Pick exactly one category from the allowed list.\n\n"
    "RULES:\n"
    '- "emi_fees" is for an actual EMI installment, bank fee, interest charge '
    'or GST/surcharge line — never just because the payee\'s name coincidentally '
    'contains letters like "fee" or "emi" (e.g. a person named "Rafeeque" is '
    "not an EMI).\n"
    '- "legal_fees" is for payments to a lawyer/legal counsel, not a '
    "coincidental name match.\n"
    "- If the payee string carries no real signal (a bare UPI handle, a "
    'generic QR ID), say so honestly with category "other" and confidence '
    '"low" rather than guessing.\n'
    f"Allowed categories: {', '.join(ALLOWED_CATEGORIES)}."
)

# Strips a trailing " (vpa@handle)" so the label pattern matches the payee's
# name across transactions, not one specific VPA string.
_TRAILING_PAREN = re.compile(r"\s*\([^)]*\)\s*$")


@dataclass
class PayeeClassification:
    category: str
    confidence: str
    reason: str


def _label_pattern(counterparty: str) -> str:
    return _TRAILING_PAREN.sub("", counterparty).strip()


def classify_payee(counterparty: str, client: OpenClawClient | None = None) -> PayeeClassification | None:
    """Best-effort: returns None if OpenClaw is unreachable or the response
    doesn't parse. Never raises — callers should keep going without a label
    rather than fail a sync over this.
    """
    if not counterparty:
        return None

    client = client or OpenClawClient()
    try:
        client.timeout = min(client.timeout, _CLASSIFY_TIMEOUT_SECONDS)
    except Exception:  # client may be a test stub
        pass

    messages = [
        {"role": "system", "content": _SYSTEM_PROMPT},
        {"role": "user", "content": f"Payee: {counterparty}"},
    ]
    try:
        raw = client.chat_structured(messages, schema=_SCHEMA, schema_name="payee_category")
    except OpenClawUnavailable as exc:
        logger.info("payee classification skipped (OpenClaw unavailable): %s", exc)
        return None

    try:
        data = json.loads(raw)
        category = str(data["category"])
        confidence = str(data["confidence"])
        reason = str(data.get("reason", ""))
    except (KeyError, ValueError, TypeError):
        logger.info("payee classification skipped (unparseable response): %r", raw)
        return None

    if category not in ALLOWED_CATEGORIES or confidence not in ("high", "medium", "low"):
        return None
    return PayeeClassification(category=category, confidence=confidence, reason=reason[:200])


def suggest_labels_for_new_payees(
    db: Session,
    transactions: list[Transaction],
    client: OpenClawClient | None = None,
    max_payees: int = _MAX_PAYEES_PER_RUN,
) -> int:
    """Classify distinct debit payees among ``transactions`` that no existing
    payee_label (active or not) already covers, and write the results as new
    labels. Returns the number of labels created.

    Only looks at debits — credits never count as spend, so they don't need a
    spend_category. Skips payees an existing label already matches so a
    payee already reviewed once (accepted or left pending) is never
    reclassified on every sync.

    ``max_payees`` defaults to the small per-sync cap; a one-off bulk rebuild
    over months of history can pass a much larger value.
    """
    if not settings.openclaw_payee_classification_enabled:
        return 0

    repo = PayeeLabelRepository(db)
    # All labels, active or not — a payee already reviewed once (accepted *or*
    # left pending) must not be re-classified on every sync. Grows as this run
    # creates new labels so duplicate payees within the same batch are caught
    # by real match semantics, not just an exact-string check.
    existing = repo.list_all()
    seen_patterns: set[str] = set()
    created = 0
    client = client or OpenClawClient()

    for txn in transactions:
        if created >= max_payees:
            break
        if txn.direction != "debit" or not txn.counterparty:
            continue
        if txn.category is not None:
            continue  # already tagged transfer/lent_returned/etc. by reconciliation
        if _any_label_matches(existing, txn.counterparty):
            continue
        pattern = _label_pattern(txn.counterparty)
        if not pattern or pattern in seen_patterns:
            continue
        seen_patterns.add(pattern)

        result = classify_payee(txn.counterparty, client=client)
        if result is None:
            continue

        label = repo.create(
            pattern=pattern,
            category=result.category,
            match_type="substring",
            # Evaluated after user-authored rules (default priority 100) so an
            # explicit human override always wins over an LLM guess.
            priority=150,
            active=result.confidence == "high",
            note=f"llm-suggested ({result.confidence} confidence): {result.reason}"[:256],
        )
        existing.append(label)
        created += 1

    if created:
        db.commit()
    return created


def _any_label_matches(labels, counterparty: str) -> bool:
    lowered = counterparty.lower()
    for lbl in labels:
        needle = lbl.pattern.lower()
        if lbl.match_type == "exact":
            if lowered == needle:
                return True
        elif lbl.match_type == "regex":
            try:
                if re.search(lbl.pattern, counterparty, re.I):
                    return True
            except re.error:
                continue
        else:  # substring
            if needle in lowered:
                return True
    return False
