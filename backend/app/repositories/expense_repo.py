from __future__ import annotations

import re
from datetime import date, timedelta
from decimal import Decimal

from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session

from app.account_identity import normalize_account
from app.models.sync_state import CollectorState
from app.models.transaction import Transaction
from app.models.transaction_adjustment import TransactionAdjustment
from app.models.transaction_reconciliation import TransactionReconciliation
from app.services.expense_parsers.base import ParsedTxn

# Mirrors services.reconcile._norm — duplicated rather than imported to keep
# repositories from depending on services. Strips UPI-/honorific prefixes,
# digits and punctuation so "ZEE ENTERTA" and "Zee Entertainment Enterprises
# Limited" normalize to a comparable prefix.
_NOISE = re.compile(r"^(upi[-/]?|mr\.?|mrs\.?|m/s)\s*", re.I)


def _norm_counterparty(name: str | None) -> str:
    s = _NOISE.sub("", (name or "").strip())
    s = re.sub(r"[^a-zA-Z@ ]+", " ", s)
    return " ".join(s.lower().split())


def _statement_party_norm(name: str | None) -> str:
    """Normalize an EMI statement narration for comparison with a live alert."""
    normalized = _norm_counterparty(name)
    return re.sub(r"^(?:emi|mer emi)\s+", "", normalized).strip()


def _account_key(account: str | None) -> tuple[str, str]:
    """Bank token + trailing card/account digits, tolerant of label wording."""
    text = account or ""
    bank = text.split()[0].casefold() if text.split() else ""
    match = re.search(r"\.\.\s*(\d+)\s*$", text)
    return bank, match.group(1) if match else ""


# Debit categories that are NOT real spending: money moved between the user's
# own accounts, reversed charges, and lent money returned. Excluded from the
# daily-spend signal so behavioral correlations aren't distorted by shuffling.
_NON_SPEND_CATEGORIES = ("transfer", "lent_returned", "reversed_debit")


class ExpenseRepository:
    # Sources generated from individual live transaction alerts. Historical
    # rebuilds are always scoped to these so they cannot touch statement data.
    LIVE_SOURCES = ("upi", "hdfc_card_live", "card_live", "bank_live")

    def __init__(self, db: Session) -> None:
        self.db = db

    # -- transactions ------------------------------------------------------------

    def has_message(self, message_id: str) -> bool:
        """True if we already persisted any transaction from this email."""
        count = self.db.execute(
            select(func.count())
            .select_from(Transaction)
            .where(Transaction.gmail_message_id == message_id)
        ).scalar_one()
        return count > 0

    # Statement-derived sources. ``cc_statement``/``bank_account`` are legacy;
    # new syncs retain only selected EMI rows under ``statement_emi``.
    STATEMENT_SOURCES = ("cc_statement", "bank_account", "statement_emi")

    def statement_txn_exists(self, txn_date: date, amount) -> bool:
        count = self.db.execute(
            select(func.count())
            .select_from(Transaction)
            .where(
                Transaction.source.in_(self.STATEMENT_SOURCES),
                Transaction.txn_date == txn_date,
                Transaction.amount == amount,
            )
        ).scalar_one()
        return count > 0

    def live_coverage_exists(
        self, account: str | None, txn_date: date, amount, direction: str, counterparty: str | None
    ) -> bool:
        """True when a live alert already covers a proposed statement EMI row.

        Banks can shift the posting date by a day or settle a foreign amount a
        few rupees differently. Require the same bank/card identity and an
        overlapping merchant name before applying that narrow tolerance.
        """
        account_key = _account_key(account)
        new_party = _statement_party_norm(counterparty)
        if not all(account_key) or not new_party:
            return False
        candidates = self.db.execute(
            select(Transaction).where(
                Transaction.source.in_(self.LIVE_SOURCES),
                Transaction.direction == direction,
                Transaction.txn_date >= txn_date - timedelta(days=1),
                Transaction.txn_date <= txn_date + timedelta(days=1),
            )
        ).scalars()
        amount_tolerance = max(Decimal("5.00"), amount * Decimal("0.01"))
        for existing in candidates:
            if _account_key(existing.account) != account_key:
                continue
            if abs(existing.amount - amount) > amount_tolerance:
                continue
            existing_party = _statement_party_norm(existing.counterparty)
            if existing_party and (existing_party in new_party or new_party in existing_party):
                return True
        return False

    def unreferenced_live_txn_exists(
        self, account: str | None, txn_date: date, amount, direction: str, counterparty: str | None
    ) -> bool:
        """True if a transaction with no reference number, the same
        (account, date, amount, direction), AND an overlapping counterparty
        name already exists.

        Some banks send *two separate emails* for one real transaction (e.g.
        Axis emails a generic "card spent" alert AND a distinct "AutoPay
        summary" alert for the same mandate payment, one truncating the
        merchant name to "ZEE ENTERTA" and the other spelling out "Zee
        Entertainment Enterprises Limited") — neither carries a UPI reference
        number, so the normal reference-based dedup_key can't tie them
        together, and each would otherwise become its own row.

        The counterparty check is NOT optional: production data shows
        account+date+amount+direction alone collides constantly between
        genuinely different transactions — different people sending the same
        round amount the same day, two different petrol stations charging an
        identical amount, etc. Only a name match (after normalizing away
        UPI-/honorific noise) makes this safe to treat as a real duplicate.
        """
        if not account or not counterparty:
            return False
        candidates = self.db.execute(
            select(Transaction.counterparty)
            .where(
                Transaction.account == account,
                Transaction.txn_date == txn_date,
                Transaction.amount == amount,
                Transaction.direction == direction,
                Transaction.reference.is_(None),
            )
        ).scalars()
        new_norm = _norm_counterparty(counterparty)
        if not new_norm:
            return False
        for existing_counterparty in candidates:
            existing_norm = _norm_counterparty(existing_counterparty)
            if existing_norm and (existing_norm in new_norm or new_norm in existing_norm):
                return True
        return False

    def upsert(self, txn: ParsedTxn, message_id: str, dedup_key: str) -> Transaction | None:
        """Insert a transaction if new; return it, or None if it already existed.

        Does not commit — the collector commits once per sync. Idempotent on
        ``dedup_key``.
        """
        existing = self.db.execute(
            select(Transaction).where(Transaction.dedup_key == dedup_key)
        ).scalar_one_or_none()
        if existing:
            return None
        # Collapse label drift to one canonical identity per physical card at the
        # single write choke point every parser funnels through, so one card is
        # one label everywhere. Normalize BEFORE the unreferenced-dedup check:
        # a new "SBI Card ..70" must match already-canonical "SBI Card ..1970"
        # rows, not slip past as a distinct account.
        account = normalize_account(txn.account)
        if not txn.reference and self.unreferenced_live_txn_exists(
            account, txn.txn_date, txn.amount, txn.direction, txn.counterparty
        ):
            return None
        obj = Transaction(
            txn_date=txn.txn_date,
            txn_at=txn.txn_at,
            amount=txn.amount,
            currency=txn.currency,
            direction=txn.direction,
            counterparty=txn.counterparty,
            category=txn.category,
            account=account,
            source=txn.source,
            reference=txn.reference,
            gmail_message_id=message_id,
            dedup_key=dedup_key,
            raw_snippet=txn.raw_snippet,
        )
        self.db.add(obj)
        self.db.flush()
        return obj

    def delete_since(
        self, since: date, sources: tuple[str, ...], account_prefix: str | None = None
    ) -> int:
        """Delete transactions dated on/after ``since`` whose ``source`` is in
        ``sources`` — required, not defaulted, since this is destructive and
        must never silently touch sources outside a rebuild's actual scope
        (e.g. monthly statement PDFs, which a live-alert rebuild can't
        regenerate). Does not commit — caller commits once the rest of the
        rebuild step succeeds too.

        ``account_prefix``, when given, further restricts to accounts whose
        label starts with it (e.g. "HDFC") — needed when a rebuild targets
        one bank's emails only, so it can't delete a *different* bank's
        already-good rows it has no way to regenerate in the same run.
        """
        conditions = [Transaction.txn_date >= since, Transaction.source.in_(sources)]
        if account_prefix:
            conditions.append(Transaction.account.like(f"{account_prefix}%"))
        # Rebuilds must not erase an audited repayment (or fail halfway on its
        # RESTRICT foreign keys). Keeping linked source rows is safe: their
        # existing dedup keys make the re-ingest skip them, while every
        # unlinked row in the requested scope is still rebuilt normally.
        linked_ids = select(TransactionReconciliation.debit_transaction_id).union(
            select(TransactionReconciliation.credit_transaction_id)
        )
        conditions.append(Transaction.id.notin_(linked_ids))
        adjusted_ids = select(TransactionAdjustment.transaction_id)
        conditions.append(Transaction.id.notin_(adjusted_ids))
        result = self.db.execute(delete(Transaction).where(*conditions))
        return result.rowcount or 0

    def daily_spend(self, start: date, end: date) -> dict[date, float]:
        """Total real debit outflow per day in [start, end].

        Excludes non-spend debits — self-transfers, reversed charges, and money
        lent that was returned — so the series reflects actual spending rather
        than money shuffled between the user's own accounts. Uncategorized
        debits (the bulk of real spend) are kept. Only days with such a debit
        appear; callers decide whether a missing day means "0 spent" or "no
        data" for that day.
        """
        stmt = (
            select(Transaction.txn_date, func.sum(Transaction.amount))
            .where(
                Transaction.txn_date >= start,
                Transaction.txn_date <= end,
                Transaction.direction == "debit",
                func.coalesce(Transaction.category, "").notin_(_NON_SPEND_CATEGORIES),
            )
            .group_by(Transaction.txn_date)
        )
        return {
            txn_date: float(total)
            for txn_date, total in self.db.execute(stmt)
            if total is not None
        }

    def range(self, start: date, end: date, source: str | None = None) -> list[Transaction]:
        stmt = select(Transaction).where(
            Transaction.txn_date >= start, Transaction.txn_date <= end
        )
        if source:
            stmt = stmt.where(Transaction.source == source)
        stmt = stmt.order_by(Transaction.txn_date.desc(), Transaction.id.desc())
        return list(self.db.execute(stmt).scalars())

    def set_spend_category(self, transaction_id: int, category: str | None) -> Transaction | None:
        """Persist a per-transaction user override without touching reconciliation state."""
        transaction = self.db.get(Transaction, transaction_id)
        if transaction is None:
            return None
        transaction.spend_category_override = category
        self.db.commit()
        self.db.refresh(transaction)
        return transaction

    def since_id(self, after_id: int, limit: int = 500) -> list[Transaction]:
        """Transactions inserted after ``after_id`` (client incremental polling)."""
        stmt = (
            select(Transaction)
            .where(Transaction.id > after_id)
            .order_by(Transaction.id)
            .limit(limit)
        )
        return list(self.db.execute(stmt).scalars())

    # -- sync cursor -------------------------------------------------------------

    def get_cursor(self, key: str) -> int:
        row = self.db.get(CollectorState, key)
        return row.cursor_epoch if row else 0

    def set_cursor(self, key: str, epoch: int) -> None:
        row = self.db.get(CollectorState, key)
        if row is None:
            self.db.add(CollectorState(key=key, cursor_epoch=epoch))
        elif epoch > row.cursor_epoch:
            row.cursor_epoch = epoch
