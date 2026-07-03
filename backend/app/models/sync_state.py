"""Collector sync cursors.

One row per (collector, mailbox). Stores the high-water mark so a sync fetches
only emails newer than the last processed one, instead of re-scanning a window
every time. Durable in Postgres, so restarts keep the cursor.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import BigInteger, DateTime, String
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base, utcnow


class CollectorState(Base):
    __tablename__ = "collector_state"

    key: Mapped[str] = mapped_column(String(160), primary_key=True)
    # Epoch seconds of the newest email processed for this key (Gmail internalDate).
    cursor_epoch: Mapped[int] = mapped_column(BigInteger, default=0)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow, onupdate=utcnow
    )
