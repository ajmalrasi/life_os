from __future__ import annotations

from datetime import date

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.models.sync_state import CollectorState
from app.models.transaction import Transaction
from app.services.expense_parsers.base import ParsedTxn


class ExpenseRepository:
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

    # Statement-derived sources. A live-alert transaction matching one of
    # these by date+amount is the same real transaction already ingested from
    # a monthly statement — this can happen when a live parser is added (or
    # backfilled) after that statement period was already ingested. Normal
    # operation has the live alert arrive first, so this mainly guards
    # backfills; the reverse case (statement line vs. existing live row) is
    # handled separately in StatementRepository.upi_txn_exists.
    STATEMENT_SOURCES = ("cc_statement", "bank_account")

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
        obj = Transaction(
            txn_date=txn.txn_date,
            amount=txn.amount,
            currency=txn.currency,
            direction=txn.direction,
            counterparty=txn.counterparty,
            category=txn.category,
            account=txn.account,
            source=txn.source,
            reference=txn.reference,
            gmail_message_id=message_id,
            dedup_key=dedup_key,
            raw_snippet=txn.raw_snippet,
        )
        self.db.add(obj)
        self.db.flush()
        return obj

    def range(self, start: date, end: date, source: str | None = None) -> list[Transaction]:
        stmt = select(Transaction).where(
            Transaction.txn_date >= start, Transaction.txn_date <= end
        )
        if source:
            stmt = stmt.where(Transaction.source == source)
        stmt = stmt.order_by(Transaction.txn_date.desc(), Transaction.id.desc())
        return list(self.db.execute(stmt).scalars())

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
