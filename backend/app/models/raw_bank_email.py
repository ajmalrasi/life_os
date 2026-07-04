"""Raw bank/UPI emails, fetched but not yet run through the LLM chain.

Separates the two failure-prone halves of a historical rebuild: Gmail I/O
(network-bound, needs to happen exactly once per email) from LLM
classification (slow, one call per email, the part most likely to be
interrupted by a dropped connection or a Jetson hiccup). Fetching is cheap
and idempotent on ``message_id``; processing can be retried in small batches
without ever re-hitting Gmail, and picks up exactly where it left off.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import BigInteger, DateTime, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base, utcnow


class RawBankEmail(Base):
    __tablename__ = "raw_bank_emails"

    id: Mapped[int] = mapped_column(primary_key=True)

    message_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    bank: Mapped[str] = mapped_column(String(32), index=True)  # "HDFC" | "Axis"
    subject: Mapped[str] = mapped_column(String(512), default="")
    from_addr: Mapped[str] = mapped_column(String(256), default="")
    received: Mapped[str] = mapped_column(String(128), default="")
    text: Mapped[str] = mapped_column(Text)
    # Gmail internalDate in epoch *milliseconds* — a 13-digit value that
    # overflows a 32-bit Postgres integer, unlike CollectorState.cursor_epoch
    # which stores epoch seconds.
    internal_date_ms: Mapped[int] = mapped_column(BigInteger, default=0)

    fetched_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    # NULL until the LLM chain has made a decision on this email (whether or
    # not that decision produced a transaction) — the queue of remaining work.
    processed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), default=None)
