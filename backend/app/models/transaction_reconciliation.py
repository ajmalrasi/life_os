"""Audited links between financial transactions.

The source bank rows remain immutable.  A reconciliation records why two rows
were taken out of financial totals and keeps enough prior state to undo a
manual decision safely.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Index, Integer, String, text
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base, utcnow


class TransactionReconciliation(Base):
    __tablename__ = "transaction_reconciliations"
    __table_args__ = (
        # A bank row may belong to only one active repayment.  Partial indexes
        # allow an undone pairing to remain in the audit trail and be redone.
        Index(
            "uq_active_reconciliation_debit",
            "debit_transaction_id",
            unique=True,
            postgresql_where=text("undone_at IS NULL"),
            sqlite_where=text("undone_at IS NULL"),
        ),
        Index(
            "uq_active_reconciliation_credit",
            "credit_transaction_id",
            unique=True,
            postgresql_where=text("undone_at IS NULL"),
            sqlite_where=text("undone_at IS NULL"),
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    kind: Mapped[str] = mapped_column(String(32), default="friend_repayment")
    source: Mapped[str] = mapped_column(String(16), default="manual")
    debit_transaction_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("transactions.id", ondelete="RESTRICT"), nullable=False
    )
    credit_transaction_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("transactions.id", ondelete="RESTRICT"), nullable=False
    )
    previous_debit_category: Mapped[str | None] = mapped_column(String(64), default=None)
    previous_credit_category: Mapped[str | None] = mapped_column(String(64), default=None)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    undone_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), default=None)
