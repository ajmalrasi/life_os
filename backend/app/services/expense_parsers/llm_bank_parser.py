"""LLM-driven catch-all parser for bank/UPI live-alert emails.

Every other parser in this package is a hand-written regex tied to one bank's
exact wording — reliable when it matches, but silently blind to anything that
doesn't (that's exactly how the Mehbooba Parambil credit went missing: the
alert's subject didn't contain "UPI", so `hdfc_bank_upi.py`'s ``gmail_query``
never even fetched it). This parser trades the narrow subject-regex query for
a much wider one (bank domain only) and asks OpenClaw to do the classification
and field extraction instead.

Two-stage chain, not one big pass:

1. ``_is_transaction`` gates on the FULL body (subject alone was tested and
   found unreliable — it missed the exact real Mehbooba email). Marketing,
   OTP, KYC, and statement-ready emails from the same bank domain are common
   and must never be touched; a bad gate here is worse than a bad field
   extraction, since it would fabricate a transaction that never happened.
2. ``_extract_fields`` only runs if (1) said yes, and pulls every remaining
   field (direction, bank, amount, counterparty, card/UPI/RuPay-UPI flags,
   date, reference) in one schema-constrained call — validated in testing to
   be reliable when combined, so there's no accuracy reason to split it
   further and pay for extra round-trips.

Best-effort throughout: any OpenClaw failure or unparseable response simply
skips the email (``parse()`` returns ``[]``) rather than raising, so this
running alongside the regex parsers can never break a sync.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal, InvalidOperation

from app.config import get_settings
from app.services.expense_parsers.base import BankEmail, ParsedTxn
from app.services.openclaw import OpenClawClient, OpenClawUnavailable

logger = logging.getLogger("lifeos.llm_bank_parser")
settings = get_settings()

_TIMEOUT_SECONDS = 30.0

_GATE_SCHEMA = {
    "type": "object",
    "properties": {
        "is_transaction": {"type": "boolean"},
        "reason": {"type": "string"},
    },
    "required": ["is_transaction", "reason"],
    "additionalProperties": False,
}

_GATE_PROMPT = (
    "You are given the full body of an email from a bank. Decide whether it "
    "reports a SPECIFIC transaction that already happened — an actual debit or "
    "credit with an amount — as opposed to a statement summary, promotional "
    "offer, KYC/service notice, OTP, due-date reminder, or anything else that "
    "isn't itself a completed transaction.\n\n"
    'Return ONLY {"is_transaction": true/false, "reason": "<short phrase>"}.'
)

_FIELDS_SCHEMA = {
    "type": "object",
    "properties": {
        "direction": {"type": "string", "enum": ["debit", "credit"]},
        "bank": {"type": "string"},
        "amount": {"type": "number"},
        "counterparty_name": {"type": "string"},
        "counterparty_vpa": {"type": "string"},
        "is_card": {"type": "boolean"},
        "is_upi": {"type": "boolean"},
        "is_rupay_upi": {"type": "boolean"},
        "last4": {"type": "string"},
        "txn_date": {"type": "string"},
        "reference": {"type": "string"},
    },
    "required": [
        "direction", "bank", "amount", "counterparty_name", "counterparty_vpa",
        "is_card", "is_upi", "is_rupay_upi", "last4", "txn_date", "reference",
    ],
    "additionalProperties": False,
}

_FIELDS_PROMPT = (
    "Extract the transaction facts from this bank email, grounded ONLY in "
    "what it actually says.\n\n"
    "- direction: a purchase/payment/debit is \"debit\"; money arriving is "
    '"credit". A card "payment was made" or "purchase" is always a debit, '
    "never a credit.\n"
    "- amount: the specific transaction amount — never an available balance, "
    "credit limit, minimum due, or total outstanding mentioned elsewhere in "
    "the same email.\n"
    "- counterparty_name: ONLY the other party's name (sender for a credit, "
    'payee for a debit) — e.g. "MEHBOOBA PARAMBIL" or "NESTO HYPERMARKET". '
    'Never include the VPA/handle here. "" if no name is given.\n'
    '- counterparty_vpa: ONLY the UPI VPA/handle (e.g. "name@bank"), or "" if '
    "none is given. Never put this in counterparty_name.\n"
    "- is_card: true only if this is a credit/debit CARD transaction.\n"
    "- is_upi: true if this moved over UPI rails.\n"
    "- is_rupay_upi: true only if this is specifically a RuPay CARD used via "
    "UPI (distinct from a plain bank-account UPI transaction).\n"
    "- last4: the last 4 digits of the account/card, or \"\" if not stated.\n"
    "- txn_date: YYYY-MM-DD. Banks write dates as DD-MM-YY or DD-MM-YYYY "
    '(e.g. "04-07-26" is 4th July 2026, NOT April 7th).\n'
    "- reference: the UPI reference number / transaction ID if present, else "
    '"".\n\n'
    "Return ONLY a JSON object with exactly these keys: direction, bank, "
    "amount, counterparty_name, counterparty_vpa, is_card, is_upi, "
    "is_rupay_upi, last4, txn_date, reference."
)

# Bank domains the existing regex parsers already cover — kept as one shared
# net so this parser's broader (no-subject-filter) query still only ever
# fetches from banks you actually use, not the whole mailbox. Keyed by bank
# name (matching the "HDFC ..."/"Axis ..." prefix ParsedTxn.account labels
# use) so a scoped historical rebuild can target one bank's emails only —
# different banks can have very different statement coverage, so they need
# different "since" dates.
BANK_DOMAINS = {"HDFC": "alerts@hdfcbank.bank.in", "Axis": "alerts@axis.bank.in"}


@dataclass
class _ExtractedFields:
    direction: str
    bank: str
    amount: Decimal
    counterparty_name: str
    counterparty_vpa: str
    is_card: bool
    is_upi: bool
    is_rupay_upi: bool
    last4: str
    txn_date: date
    reference: str

    @property
    def counterparty(self) -> str | None:
        # Built deterministically in code, never trusting the model's own
        # combined-string formatting — that varied between "vpa (NAME)" and
        # "NAME (VPA: vpa)" across calls in testing, which broke
        # reconcile.py's name-matching between a debit and its later credit
        # (the two sides of one real pair must render identically to match).
        name, vpa = self.counterparty_name.strip(), self.counterparty_vpa.strip()
        if name and vpa:
            return f"{name} ({vpa})"
        return name or vpa or None


# The prompt asks for YYYY-MM-DD, and that's the schema's declared shape, but
# a JSON schema only constrains a string field's *type*, not its format — in
# testing, the model reliably returns ISO dates in isolation but sometimes
# echoes the email's raw DD-MM-YY when it's one field among many in a larger
# extraction call. Fall back through the raw bank formats rather than
# discarding an otherwise-good extraction over this one field.
_DATE_FORMATS = ("%Y-%m-%d", "%d-%m-%y", "%d-%m-%Y", "%d %b, %Y", "%d %b %Y")


def _parse_txn_date(raw: str) -> date | None:
    raw = (raw or "").strip()
    for fmt in _DATE_FORMATS:
        try:
            return datetime.strptime(raw, fmt).date()
        except ValueError:
            continue
    return None


def _is_transaction(text: str, client: OpenClawClient) -> bool | None:
    """None means "couldn't tell" (OpenClaw down / bad response) — caller
    must treat that as "don't touch it", same as a firm false."""
    messages = [
        {"role": "system", "content": _GATE_PROMPT},
        {"role": "user", "content": "EMAIL:\n" + text[:4000]},
    ]
    try:
        raw = client.chat_structured(messages, schema=_GATE_SCHEMA, schema_name="is_transaction")
    except OpenClawUnavailable as exc:
        logger.info("llm_bank_parser gate skipped (OpenClaw unavailable): %s", exc)
        return None
    try:
        return bool(json.loads(raw)["is_transaction"])
    except (KeyError, ValueError, TypeError):
        logger.info("llm_bank_parser gate skipped (unparseable response): %r", raw)
        return None


def _extract_fields(text: str, client: OpenClawClient) -> _ExtractedFields | None:
    messages = [
        {"role": "system", "content": _FIELDS_PROMPT},
        {"role": "user", "content": "EMAIL:\n" + text[:4000]},
    ]
    try:
        raw = client.chat_structured(messages, schema=_FIELDS_SCHEMA, schema_name="txn_fields")
    except OpenClawUnavailable as exc:
        logger.info("llm_bank_parser extraction skipped (OpenClaw unavailable): %s", exc)
        return None

    try:
        data = json.loads(raw)
        amount = Decimal(str(data["amount"]))
        txn_date = _parse_txn_date(data["txn_date"])
        if txn_date is None:
            logger.info("llm_bank_parser extraction skipped (unparseable date): %r", data["txn_date"])
            return None
        return _ExtractedFields(
            direction=str(data["direction"]),
            bank=str(data["bank"]).strip() or "unknown",
            amount=amount,
            counterparty_name=str(data["counterparty_name"]).strip(),
            counterparty_vpa=str(data["counterparty_vpa"]).strip(),
            is_card=bool(data["is_card"]),
            is_upi=bool(data["is_upi"]),
            is_rupay_upi=bool(data["is_rupay_upi"]),
            last4=str(data.get("last4", "")).strip(),
            txn_date=txn_date,
            reference=str(data.get("reference", "")).strip(),
        )
    except (KeyError, ValueError, TypeError, InvalidOperation):
        logger.info("llm_bank_parser extraction skipped (unparseable response): %r", raw)
        return None


def _account_label(fields: _ExtractedFields) -> str | None:
    if not fields.last4:
        return None
    kind = "Card" if fields.is_card else "Savings"
    return f"{fields.bank} {kind} ..{fields.last4}"


def _source(fields: _ExtractedFields) -> str:
    if fields.is_upi:
        return "upi"
    if fields.is_card:
        return "card_live"
    # NOT "bank_account" — that source name is already owned by the separate
    # statement-PDF pipeline (statement_parsers/bank_accounts.py) and means
    # something different there (a monthly bank-statement line, not a live
    # alert). Reusing it would make a rebuild's delete step destroy statement
    # history this pipeline can't regenerate.
    return "bank_live"


class LlmBankParser:
    name = "llm_bank_parser"

    def __init__(self, client: OpenClawClient | None = None, domains: list[str] | None = None) -> None:
        self._client = client
        # Overridable so a scoped historical rebuild can target one bank at a
        # time — different banks have wildly different statement coverage
        # (e.g. HDFC's live-alert accounts are already fully covered by
        # statements through a recent date, while Axis Savings has no
        # statement equivalent at all), so they need different "since" dates.
        self.gmail_query = "from:(" + " OR ".join(domains or BANK_DOMAINS.values()) + ")"

    def _get_client(self) -> OpenClawClient:
        client = self._client or OpenClawClient()
        try:
            client.timeout = min(client.timeout, _TIMEOUT_SECONDS)
        except Exception:  # client may be a test stub
            pass
        return client

    def parse(self, email: BankEmail) -> list[ParsedTxn]:
        if not settings.llm_bank_parser_enabled:
            return []

        client = self._get_client()
        if not _is_transaction(email.text, client):
            return []

        fields = _extract_fields(email.text, client)
        if fields is None or fields.direction not in ("debit", "credit"):
            return []
        if fields.amount <= 0:
            return []

        return [
            ParsedTxn(
                txn_date=fields.txn_date,
                amount=fields.amount,
                direction=fields.direction,
                source=_source(fields),
                account=_account_label(fields),
                counterparty=fields.counterparty or None,
                reference=fields.reference or None,
                raw_snippet=email.text[:400],
            )
        ]
