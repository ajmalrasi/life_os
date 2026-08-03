"""Audited one-sided adjustments to source financial transactions.

Unlike a repayment reconciliation, a manual merchant refund may need to be
recorded before LifeOS has an incoming bank-credit row to pair with it.  The
source transaction stays intact; this table records the user's decision and
the category that must be restored if they undo it.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Index, Integer, String, text
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base, utcnow


class TransactionAdjustment(Base):
    __tablename__ = "transaction_adjustments"
    __table_args__ = (
        Index(
            "uq_active_transaction_adjustment",
            "transaction_id",
            unique=True,
            postgresql_where=text("undone_at IS NULL"),
            sqlite_where=text("undone_at IS NULL"),
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    kind: Mapped[str] = mapped_column(String(32), default="merchant_refund")
    source: Mapped[str] = mapped_column(String(16), default="manual")
    transaction_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("transactions.id", ondelete="RESTRICT"), nullable=False
    )
    previous_category: Mapped[str | None] = mapped_column(String(64), default=None)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    undone_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), default=None)
