"""Thread-level Priority state, source links, corrections, and push ledger."""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import Boolean, DateTime, Float, ForeignKey, JSON, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base, utcnow


class PriorityItem(Base):
    __tablename__ = "priority_items"
    __table_args__ = (
        UniqueConstraint("mail_account_id", "thread_key", name="uq_priority_account_thread"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    mail_account_id: Mapped[int] = mapped_column(
        ForeignKey("mail_accounts.id", ondelete="CASCADE"), index=True
    )
    thread_key: Mapped[str] = mapped_column(String(160))
    latest_mail_message_id: Mapped[int] = mapped_column(
        ForeignKey("mail_messages.id", ondelete="CASCADE"), index=True
    )
    subject: Mapped[str] = mapped_column(String(500))
    sender: Mapped[str] = mapped_column(String(500))
    company: Mapped[str | None] = mapped_column(String(200), nullable=True)
    role: Mapped[str | None] = mapped_column(String(240), nullable=True)
    importance: Mapped[str] = mapped_column(String(24), index=True)
    automatic_labels: Mapped[list[str]] = mapped_column(JSON, default=list)
    automatic_state: Mapped[str] = mapped_column(String(32), index=True)
    confidence: Mapped[float] = mapped_column(Float, default=0.0)
    summary: Mapped[str] = mapped_column(String(800))
    why_important: Mapped[str] = mapped_column(String(800))
    evidence: Mapped[list[str]] = mapped_column(JSON, default=list)
    action_text: Mapped[str | None] = mapped_column(String(300), nullable=True)
    action_url: Mapped[str | None] = mapped_column(Text, nullable=True)
    due_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True, index=True)
    event_start: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True, index=True)
    event_end: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    timezone: Mapped[str | None] = mapped_column(String(64), nullable=True)
    location: Mapped[str | None] = mapped_column(String(500), nullable=True)
    meeting_url: Mapped[str | None] = mapped_column(Text, nullable=True)
    calendar_uid: Mapped[str | None] = mapped_column(String(300), nullable=True)
    has_calendar_invite: Mapped[bool] = mapped_column(Boolean, default=False)
    gmail_url: Mapped[str] = mapped_column(Text)
    preview: Mapped[str] = mapped_column(Text, default="")
    received_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)

    # User-owned values remain separate from automatic classification.
    user_state: Mapped[str | None] = mapped_column(String(32), nullable=True)
    user_labels: Mapped[list[str] | None] = mapped_column(JSON, nullable=True)
    user_importance: Mapped[str | None] = mapped_column(String(24), nullable=True)
    snoozed_until: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow, onupdate=utcnow
    )

    @property
    def state(self) -> str:
        return self.user_state or self.automatic_state

    @property
    def labels(self) -> list[str]:
        return self.user_labels if self.user_labels is not None else self.automatic_labels

    @property
    def effective_importance(self) -> str:
        return self.user_importance or self.importance


class PriorityItemMessage(Base):
    __tablename__ = "priority_item_messages"
    __table_args__ = (
        UniqueConstraint("priority_item_id", "mail_message_id", name="uq_priority_item_message"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    priority_item_id: Mapped[int] = mapped_column(
        ForeignKey("priority_items.id", ondelete="CASCADE"), index=True
    )
    mail_message_id: Mapped[int] = mapped_column(
        ForeignKey("mail_messages.id", ondelete="CASCADE"), index=True
    )
    classification_snapshot: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class PriorityFeedback(Base):
    __tablename__ = "priority_feedback"

    id: Mapped[int] = mapped_column(primary_key=True)
    priority_item_id: Mapped[int] = mapped_column(
        ForeignKey("priority_items.id", ondelete="CASCADE"), index=True
    )
    kind: Mapped[str] = mapped_column(String(40))
    value: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class PriorityNotification(Base):
    __tablename__ = "priority_notifications"
    __table_args__ = (
        UniqueConstraint("priority_item_id", "event_key", name="uq_priority_notification_event"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    priority_item_id: Mapped[int] = mapped_column(
        ForeignKey("priority_items.id", ondelete="CASCADE"), index=True
    )
    event_key: Mapped[str] = mapped_column(String(160))
    delivered_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
