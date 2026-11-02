"""Post-ingest reconciliation over the transactions table.

Three jobs, all idempotent (they only touch rows with category IS NULL):

1. ``tag_transfers`` — credit-card bill payments and inter-account moves are
   money shuffling, not spend. Tagged ``transfer`` so analysis excludes them.

2. ``reconcile_returns`` — money lent and returned (user sends someone money
   temporarily; they pay it back). A debit and a later credit with the SAME
   amount and the SAME normalized counterparty within ``window_days`` are
   paired: debit tagged ``lent_returned``, credit tagged ``lend_return``.
   Both are excluded from spend. Each credit pairs with at most one debit.

3. ``reconcile_reversals`` — a bank-side UPI failure/reversal (e.g. SBI's
   "UPI/DR/<ref>/..." followed by "UPI/REV/<ref>"). Unlike (2) this is matched
   by the UPI reference number embedded in the narration, not the counterparty
   name, because the bank's own reversal line carries no name — just the ref.
   Debit tagged ``reversed_debit``, credit tagged ``reversal_credit``.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.models.transaction import Transaction
from app.models.transaction_reconciliation import TransactionReconciliation

# Categories that must never count as spend.
EXCLUDED_FROM_SPEND = {"transfer", "lent_returned", "lend_return", "reversed_debit", "reversal_credit", "investments", "duplicate"}

# Investment contributions are asset movements, not consumption.
_INVESTMENT_PARTY = re.compile(r"zerodha|\bbroking\b|groww|smallcase|mutual\s*fund", re.I)


def is_investment(txn, categorize=None) -> bool:
    if txn.spend_category_override is not None:
        return txn.spend_category_override == "investments"
    if categorize is not None and categorize(txn.counterparty or "") == "investments":
        return True
    return bool(_INVESTMENT_PARTY.search(txn.counterparty or "") or
                _INVESTMENT_PARTY.search(txn.raw_snippet or ""))


# Credits matching this are money arriving from yourself (card bill payments,
# sweep-ins) — never income/spend.
_TRANSFER_CREDIT_PAT = re.compile(
    r"BBPS|CC PAYMENT|PAYMENT RECEIVED|CREDIT CARD PAYMENT|BPPY|CRED CLUB|AUTO SWEEP|SWEEP TRF|REV SWEEP",
    re.I,
)
# Debits matching this are you paying your own card / moving money between
# accounts. Deliberately NOT bare "BBPS": paying an electricity bill via BBPS
# with a card is real spend; only card-bill payments are transfers.
#
# CRED (cred.club) is a UPI app used specifically to pay credit-card bills, so
# a UPI debit to it is a transfer, not spend, even though it looks like an
# ordinary merchant payment. \bCRED\b / @cred require "cred" as a standalone
# word or VPA handle so this doesn't match "credit", "credited", etc.
_TRANSFER_DEBIT_PAT = re.compile(
    r"CC PAYMENT|CREDIT CARD PAYMENT|BPPY CC|CRED CLUB|AUTO SWEEP|SWEEP TRF|CREDIT CARD BILL"
    r"|@cred\b|\bCRED\b|\bDREAMPLUG\b",
    re.I,
)

_NOISE = re.compile(r"^(upi[-/]?|mr\.?|mrs\.?|m/s)\s*", re.I)

_REVERSAL_PAT = re.compile(r"\bREV\b", re.I)
_REF_ID = re.compile(r"\b(\d{9,15})\b")

# Generic institutional/clearing-house descriptors, not a person's name. Bank
# statements print the SAME text for every transaction routed through these
# rails (e.g. every SIP/mandate debit reads "ACH D- Indian Clearing..."), so
# matching on this text pairs unrelated transactions that merely share a
# payment rail — not evidence of lending money to a person and it coming back.
_INSTITUTIONAL_PAT = re.compile(
    r"\bACH\b|\bNACH\b|\bNEFT\b|\bIMPS\b|\bRTGS\b|CLEARING|MUTUAL FUND|ZERODHA|BROKING|"
    r"\bSIP\b|INSURANCE|PREMIUM",
    re.I,
)


def _norm(name: str | None) -> str:
    """Normalize a counterparty for matching: drop UPI-/honorific prefixes,
    digits and punctuation, collapse case/whitespace."""
    s = _NOISE.sub("", (name or "").strip())
    s = re.sub(r"[^a-zA-Z@ ]+", " ", s)
    return " ".join(s.lower().split())


@dataclass
class ReconcileReport:
    transfers_tagged: int
    return_pairs: int
    reversals_tagged: int = 0


def _compact(name: str | None) -> str:
    """Letters only, lowercased — 'AJMAL RASI K P' -> 'ajmalrasikp'. Collapses
    spacing/punctuation so a clipped bank name compares by prefix."""
    return re.sub(r"[^a-z]", "", (name or "").lower())


def _owner_identities(identities: list[str]) -> tuple[set[str], set[str]]:
    """Split configured owner identities into (vpa_handle_locals, name_compacts).

    A handle like ``ajmalrasi@upi`` contributes its local part ``ajmalrasi`` so
    it still matches when the PSP suffix drifts (``ajmalrasi@okhdfcbank``)."""
    handles: set[str] = set()
    names: set[str] = set()
    for ident in identities:
        ident = ident.strip()
        if not ident:
            continue
        if "@" in ident:
            local = _compact(ident.split("@", 1)[0])
            if local:
                handles.add(local)
        else:
            c = _compact(ident)
            if len(c) >= 4:  # a bare initial or two isn't identifying enough
                names.add(c)
    return handles, names


def _is_self(counterparty: str | None, snippet: str | None, handles: set[str], names: set[str]) -> bool:
    """True when the counterparty is the account owner — i.e. money moved
    between the owner's own accounts (a self-transfer), not spend/income.

    Names are matched truncation-tolerantly: a self-transfer's counterparty is
    the owner's own name, which banks routinely clip ("AJMAL RASI K P" arrives
    as "AJMAL RAS" on the other leg), so a configured name and the clipped name
    match when either is a prefix of the other (min 6 chars, to avoid a short
    shared prefix pairing two different people)."""
    blob = f"{counterparty or ''} {snippet or ''}".lower()
    for local in handles:
        # VPA local part immediately followed by '@' (a UPI handle in the text).
        if re.search(rf"\b{re.escape(local)}\s*@", blob):
            return True
    # Leading name segment: drop a "UPI-"/honorific marker, then take the name
    # up to the first VPA/parenthetical/reference/hyphen delimiter. Banks emit
    # both "NAME (vpa)" and "UPI-NAME-vpa" layouts for the same self-transfer.
    cleaned = _NOISE.sub("", (counterparty or "").strip())
    lead = _compact(re.split(r"[(@0-9-]", cleaned, maxsplit=1)[0])
    if len(lead) >= 6:
        for owner in names:
            if owner.startswith(lead) or lead.startswith(owner):
                return True
    return False


def tag_transfers(db: Session, owner_identities: list[str] | None = None) -> int:
    if owner_identities is None:
        owner_identities = get_settings().expense_owner_identities_list
    handles, names = _owner_identities(owner_identities)
    rows = list(
        db.execute(
            select(Transaction).where(Transaction.category.is_(None))
        ).scalars()
    )
    n = 0
    for t in rows:
        pat = _TRANSFER_CREDIT_PAT if t.direction == "credit" else _TRANSFER_DEBIT_PAT
        is_transfer = bool(
            pat.search(t.counterparty or "") or pat.search(t.raw_snippet or "")
        )
        # A move to/from the owner's own name/VPA is a self-transfer in either
        # direction (debit out of one bank, credit into another).
        if not is_transfer and (handles or names):
            is_transfer = _is_self(t.counterparty, t.raw_snippet, handles, names)
        if is_transfer:
            t.category = "transfer"
            n += 1
    return n


def reconcile_returns(db: Session, window_days: int = 60) -> int:
    """Pair uncategorized debit->credit (same amount, same PERSON, credit not
    earlier than debit, within the window). Returns number of pairs tagged.

    Institutional/clearing-house descriptors (ACH, NEFT, mutual funds, SIPs,
    ...) are excluded from matching even when the text is identical, because
    banks print the same generic string for every transaction on that rail —
    matching on it pairs unrelated transactions, not a person returning money.
    """
    rows = list(
        db.execute(
            select(Transaction)
            .where(Transaction.category.is_(None))
            .order_by(Transaction.txn_date, Transaction.id)
        ).scalars()
    )
    debits = [t for t in rows if t.direction == "debit"]
    credits = [t for t in rows if t.direction == "credit"]
    used: set[int] = set()
    # If the user undoes a suggested pair, do not silently recreate that exact
    # pair during the next email sync. They may still pair either side with a
    # different transaction manually.
    blocked_pairs = set(
        db.execute(
            select(
                TransactionReconciliation.debit_transaction_id,
                TransactionReconciliation.credit_transaction_id,
            ).where(TransactionReconciliation.undone_at.is_not(None))
        ).all()
    )
    pairs = 0
    for d in debits:
        if _INSTITUTIONAL_PAT.search(d.counterparty or "") or _INSTITUTIONAL_PAT.search(d.raw_snippet or ""):
            continue
        dn = _norm(d.counterparty)
        if not dn:
            continue
        for c in credits:
            if c.id in used or c.amount != d.amount:
                continue
            if (d.id, c.id) in blocked_pairs:
                continue
            if c.txn_date < d.txn_date or c.txn_date > d.txn_date + timedelta(days=window_days):
                continue
            if _INSTITUTIONAL_PAT.search(c.counterparty or "") or _INSTITUTIONAL_PAT.search(c.raw_snippet or ""):
                continue
            cn = _norm(c.counterparty)
            if not cn:
                continue
            if cn == dn or cn in dn or dn in cn:
                d.category = "lent_returned"
                c.category = "lend_return"
                db.add(
                    TransactionReconciliation(
                        kind="friend_repayment",
                        source="automatic",
                        debit_transaction_id=d.id,
                        credit_transaction_id=c.id,
                        previous_debit_category=None,
                        previous_credit_category=None,
                    )
                )
                used.add(c.id)
                pairs += 1
                break
    return pairs


def reconcile_reversals(db: Session) -> int:
    """Pair a debit with a same-day-or-later credit that share a UPI reference
    number and amount, where the credit's narration marks it a reversal
    ("REV"). Returns the number of debit+credit rows tagged."""
    rows = list(
        db.execute(
            select(Transaction).where(Transaction.category.is_(None))
        ).scalars()
    )
    debits_by_ref: dict[str, Transaction] = {}
    for t in rows:
        if t.direction != "debit":
            continue
        text = t.raw_snippet or t.counterparty or ""
        m = _REF_ID.search(text)
        if m:
            debits_by_ref.setdefault(m.group(1), t)

    n = 0
    for t in rows:
        if t.direction != "credit":
            continue
        text = t.raw_snippet or t.counterparty or ""
        if not _REVERSAL_PAT.search(text):
            continue
        m = _REF_ID.search(text)
        if not m:
            continue
        orig = debits_by_ref.get(m.group(1))
        if orig is not None and orig.amount == t.amount and orig.txn_date <= t.txn_date:
            orig.category = "reversed_debit"
            t.category = "reversal_credit"
            n += 2
    return n


def run_reconciliation(db: Session, window_days: int = 60) -> ReconcileReport:
    transfers = tag_transfers(db)
    reversals = reconcile_reversals(db)
    pairs = reconcile_returns(db, window_days=window_days)
    db.commit()
    return ReconcileReport(transfers_tagged=transfers, return_pairs=pairs, reversals_tagged=reversals)
