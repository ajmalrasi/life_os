"""Financial transactions parsed from bank / UPI / statement emails.

One row per real-world transaction. Rows are idempotent on ``dedup_key`` so the
collector can re-run over an overlapping email window without double counting
(the UPI reference number, when present, makes the key stable across duplicate
alert emails for the same payment).
"""

from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

from sqlalchemy import Date, DateTime, Numeric, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base, utcnow


class Transaction(Base):
    __tablename__ = "transactions"
    __table_args__ = (UniqueConstraint("dedup_key", name="uq_txn_dedup"),)

    id: Mapped[int] = mapped_column(primary_key=True)

    txn_date: Mapped[date] = mapped_column(Date, index=True)
    amount: Mapped[Decimal] = mapped_column(Numeric(14, 2))  # positive magnitude
    currency: Mapped[str] = mapped_column(String(3), default="INR")
    direction: Mapped[str] = mapped_column(String(6))  # "debit" | "credit"

    # When the transaction actually happened. The bank's stated clock time when
    # the alert email carries one (combined with txn_date in IST), else the
    # alert email's arrival instant as a to-the-second proxy. Nullable: rows
    # predating this column — and any where neither source is available — have
    # no time and render date-only.
    txn_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), default=None)

    counterparty: Mapped[str | None] = mapped_column(String(256), default=None)
    category: Mapped[str | None] = mapped_column(String(64), default=None, index=True)
    account: Mapped[str | None] = mapped_column(String(64), default=None)  # e.g. "HDFC RuPay CC ..5473"
    source: Mapped[str] = mapped_column(String(32), index=True)  # "upi" | "cc_statement" | ...
    reference: Mapped[str | None] = mapped_column(String(64), default=None)  # UPI ref / txn id

    # Provenance + idempotency.
    gmail_message_id: Mapped[str] = mapped_column(String(64), index=True)
    dedup_key: Mapped[str] = mapped_column(String(128))
    raw_snippet: Mapped[str | None] = mapped_column(String(512), default=None)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
