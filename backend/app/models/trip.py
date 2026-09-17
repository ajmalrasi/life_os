"""Phone-owned trip snapshots used for backup and restore."""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Integer, JSON, LargeBinary, String
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base, utcnow


class TripBackup(Base):
    __tablename__ = "trip_backups"

    id: Mapped[str] = mapped_column(String(80), primary_key=True)
    title: Mapped[str] = mapped_column(String(300))
    payload: Mapped[dict] = mapped_column(JSON)
    revision: Mapped[int] = mapped_column(Integer, default=1)
    last_mutation_id: Mapped[str] = mapped_column(String(80), index=True)
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)


class TripAttachmentBackup(Base):
    __tablename__ = "trip_attachment_backups"

    id: Mapped[str] = mapped_column(String(80), primary_key=True)
    trip_id: Mapped[str] = mapped_column(ForeignKey("trip_backups.id", ondelete="CASCADE"), index=True)
    display_name: Mapped[str] = mapped_column(String(500))
    content_type: Mapped[str] = mapped_column(String(120), default="application/octet-stream")
    content: Mapped[bytes] = mapped_column(LargeBinary)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)
