"""First-class connected mail accounts and durable provider message intake."""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import BigInteger, Boolean, DateTime, ForeignKey, Integer, JSON, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base, utcnow


class MailAccount(Base):
    __tablename__ = "mail_accounts"
    __table_args__ = (
        UniqueConstraint("provider", "provider_account_id", name="uq_mail_provider_account"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    provider: Mapped[str] = mapped_column(String(32), default="gmail")
    provider_account_id: Mapped[str] = mapped_column(String(320))
    display_label: Mapped[str] = mapped_column(String(80))
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow, onupdate=utcnow
    )


class MailAccountPurpose(Base):
    __tablename__ = "mail_account_purposes"
    __table_args__ = (
        UniqueConstraint("mail_account_id", "purpose", name="uq_mail_account_purpose"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    mail_account_id: Mapped[int] = mapped_column(
        ForeignKey("mail_accounts.id", ondelete="CASCADE"), index=True
    )
    purpose: Mapped[str] = mapped_column(String(32), index=True)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)


class MailMessageRecord(Base):
    """Minimal durable intake record; full message bodies are not retained."""

    __tablename__ = "mail_messages"
    __table_args__ = (
        UniqueConstraint(
            "mail_account_id", "provider_message_id", name="uq_mail_account_message"
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    mail_account_id: Mapped[int] = mapped_column(
        ForeignKey("mail_accounts.id", ondelete="CASCADE"), index=True
    )
    provider_message_id: Mapped[str] = mapped_column(String(128))
    provider_thread_id: Mapped[str | None] = mapped_column(String(128), nullable=True, index=True)
    subject: Mapped[str] = mapped_column(String(500), default="")
    sender: Mapped[str] = mapped_column(String(500), default="")
    recipients: Mapped[str] = mapped_column(Text, default="")
    received_header: Mapped[str] = mapped_column(String(256), default="")
    received_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True, index=True)
    internal_date_ms: Mapped[int] = mapped_column(BigInteger, default=0)
    provider_labels: Mapped[list[str]] = mapped_column(JSON, default=list)
    provider_snippet: Mapped[str] = mapped_column(Text, default="")
    preview: Mapped[str] = mapped_column(Text, default="")
    processing_state: Mapped[str] = mapped_column(String(32), default="pending", index=True)
    processing_attempts: Mapped[int] = mapped_column(Integer, default=0)
    last_error: Mapped[str | None] = mapped_column(String(500), nullable=True)
    fetched_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    processed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class MailSyncState(Base):
    __tablename__ = "mail_sync_states"
    __table_args__ = (
        UniqueConstraint("mail_account_id", "consumer", name="uq_mail_sync_account_consumer"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    mail_account_id: Mapped[int] = mapped_column(
        ForeignKey("mail_accounts.id", ondelete="CASCADE"), index=True
    )
    consumer: Mapped[str] = mapped_column(String(32), index=True)
    cursor_epoch: Mapped[int] = mapped_column(BigInteger, default=0)
    last_attempt_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    last_success_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    last_error: Mapped[str | None] = mapped_column(String(500), nullable=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow, onupdate=utcnow
    )


class MailAuthSession(Base):
    """Short-lived pairing state; refresh tokens remain in gog's keyring."""

    __tablename__ = "mail_auth_sessions"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    purpose: Mapped[str] = mapped_column(String(32), index=True)
    expected_email: Mapped[str] = mapped_column(String(320))
    display_label: Mapped[str] = mapped_column(String(80))
    redirect_uri: Mapped[str] = mapped_column(String(200))
    secret_hash: Mapped[str] = mapped_column(String(64))
    state: Mapped[str] = mapped_column(String(24), default="pending", index=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    last_error: Mapped[str | None] = mapped_column(String(500), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
