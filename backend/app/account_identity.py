"""Canonical identity for bank cards/accounts.

The same physical card is labeled inconsistently depending on which parser
wrote the row: a statement's generic ``"ICICI Card ..0010"`` vs a live alert's
``"ICICI Amazon ..0010"`` (name drift), or ``"SBI Card ..70"`` (a 2-digit
last4 from an older parse) vs ``"SBI Card ..1970"`` (4-digit, length drift).
That splits one physical card into several identities, which (a) makes the
payment-silence detector flag a live card's stale twin as a phantom dead
source, and (b) shows the card twice in the Android Money tab.

This module maps any such label to ONE canonical label via a small hand-kept
registry, matched on ``(bank, trailing digits)`` with *suffix* tolerance so a
2- vs 4-digit last4 for the same card still merges. It is deliberately
registry-only: an unregistered label passes through unchanged, so two genuinely
different same-bank cards can never be merged by accident. Add a registry entry
(one line) when a new same-card conflict is confirmed — the
``scripts/normalize_account_labels.py`` migration reports candidates.

This is a dependency-free leaf module (like ``app.config``) so it can be
imported by parsers, collectors, AND the repository layer without creating a
``repositories -> services`` import edge.
"""

from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass(frozen=True)
class CanonicalAccount:
    bank: str  # first-word bank token, matched case-insensitively, e.g. "ICICI"
    last4: str  # the fullest/most-specific suffix known for the card, e.g. "1970"
    label: str  # the one canonical label, e.g. "SBI Card ..1970"


# One entry per physical card that has appeared under more than one label. Keep
# ``last4`` as the *longest* suffix ever seen for the card so shorter variants
# (e.g. "..70") match it by suffix. Add a line here when the migration's
# dry-run reports an unregistered (bank, suffix) conflict.
_REGISTRY: tuple[CanonicalAccount, ...] = (
    CanonicalAccount(bank="ICICI", last4="0010", label="ICICI Amazon Pay ..0010"),
    CanonicalAccount(bank="SBI", last4="1970", label="SBI Card ..1970"),
)

_LAST4_RE = re.compile(r"\.\.\s*(\d+)\s*$")


def _parse(label: str) -> tuple[str, str]:
    """Return ``(bank token casefolded, trailing digits)`` from a label; the
    digits are ``""`` when the label carries no ``..NNNN`` suffix."""
    m = _LAST4_RE.search(label)
    digits = m.group(1) if m else ""
    parts = label.split()
    bank = parts[0].casefold() if parts else ""
    return bank, digits


def _suffix_match(a: str, b: str) -> bool:
    """True if one last4 is a suffix of the other, e.g. ``"1970"`` vs ``"70"``.
    Both must be non-empty — an absent last4 never matches anything."""
    return bool(a) and bool(b) and (a.endswith(b) or b.endswith(a))


def normalize_account(account: str | None) -> str | None:
    """Return the canonical label for ``account``, or ``account`` unchanged if
    no single registry entry claims it.

    ``None``/empty passes through. A label with no ``..NNNN`` suffix passes
    through (we key identity on the digits). Ambiguity — a short suffix that
    suffix-matches more than one registered card — is left untouched rather
    than guessed. Idempotent: a canonical label maps to itself.
    """
    if not account:
        return account
    bank, digits = _parse(account)
    if not digits:
        return account
    matches = [
        c for c in _REGISTRY if c.bank.casefold() == bank and _suffix_match(c.last4, digits)
    ]
    if len(matches) == 1:
        return matches[0].label
    return account
