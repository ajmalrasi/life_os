"""Monthly statement summaries parsed from emailed PDFs.

One row per (bank, card, statement date). Line items are counted for parser
observability; only selected EMI costs are copied into ``transactions``. This
table powers the "what do I owe this month" view.
"""

from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

from sqlalchemy import Date, DateTime, ForeignKey, Integer, Numeric, String, UniqueConstraint
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
    # User confirmation that this statement was settled before its due date.
    paid_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), default=None)

    # Detail parsing is versioned independently from the summary. Existing
    # statement PDFs can therefore be reparsed when detail extraction improves
    # without recreating or mutating the bill itself.
    details_status: Mapped[str] = mapped_column(String(16), default="pending")
    details_parser_version: Mapped[int] = mapped_column(Integer, default=0)
    details_parsed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), default=None)

    @property
    def status(self) -> str:
        """Current payment state; only the user's paid action is persisted.

        Upcoming/overdue are derived so they cannot become stale when the date
        changes while the app is offline.
        """
        if self.paid_at is not None:
            return "paid"
        if self.due_date is not None and self.due_date < date.today():
            return "overdue"
        return "upcoming"


class CardStatementItem(Base):
    """One explanatory line from a card statement.

    These rows never participate in spend calculations. The separate table is
    an intentional accounting boundary: live alerts remain the ordinary-spend
    source, while selected EMI costs are copied to ``transactions`` by the
    collector's existing audited path.
    """

    __tablename__ = "card_statement_items"
    __table_args__ = (
        UniqueConstraint("statement_id", "sequence", name="uq_stmt_item_sequence"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    statement_id: Mapped[int] = mapped_column(
        ForeignKey("card_statements.id", ondelete="CASCADE"), index=True
    )
    sequence: Mapped[int] = mapped_column(Integer)
    txn_date: Mapped[date] = mapped_column(Date, index=True)
    description: Mapped[str] = mapped_column(String(512))
    amount: Mapped[Decimal] = mapped_column(Numeric(14, 2))
    direction: Mapped[str] = mapped_column(String(8))
    item_type: Mapped[str] = mapped_column(String(24), default="purchase")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
