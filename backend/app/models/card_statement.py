"""Monthly statement summaries parsed from emailed PDFs.

One row per (bank, card, statement date). Line items are counted for parser
observability; only selected EMI costs are copied into ``transactions``. This
table powers the "what do I owe this month" view.
"""

from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

from sqlalchemy import Date, DateTime, Integer, Numeric, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base, utcnow


class CardStatement(Base):
    __tablename__ = "card_statements"
    __table_args__ = (
        UniqueConstraint("bank", "card_last4", "statement_date", name="uq_stmt_card_date"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)

    bank: Mapped[str] = mapped_column(String(16), index=True)
    card_label: Mapped[str] = mapped_column(String(64))
    card_last4: Mapped[str | None] = mapped_column(String(4), default=None)

    statement_date: Mapped[date | None] = mapped_column(Date, default=None, index=True)
    period_start: Mapped[date | None] = mapped_column(Date, default=None)
    period_end: Mapped[date | None] = mapped_column(Date, default=None)
    due_date: Mapped[date | None] = mapped_column(Date, default=None)

    total_due: Mapped[Decimal | None] = mapped_column(Numeric(14, 2), default=None)
    min_due: Mapped[Decimal | None] = mapped_column(Numeric(14, 2), default=None)
    total_spend: Mapped[Decimal | None] = mapped_column(Numeric(14, 2), default=None)

    line_items_total: Mapped[int] = mapped_column(Integer, default=0)
    # Count of statement-only EMI items retained in the transaction feed.
    line_items_ingested: Mapped[int] = mapped_column(Integer, default=0)

    gmail_message_id: Mapped[str] = mapped_column(String(64), index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
