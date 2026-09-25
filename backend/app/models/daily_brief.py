"""The model-written Today brief: one row per day, regenerated when its facts change."""

from __future__ import annotations

from datetime import date, datetime

from sqlalchemy import Date, DateTime, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base, utcnow


class DailyBrief(Base):
    __tablename__ = "daily_briefs"
    __table_args__ = (UniqueConstraint("day", name="uq_daily_brief_day"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    day: Mapped[date] = mapped_column(Date, index=True)

    state: Mapped[str] = mapped_column(String(32))
    confidence: Mapped[str] = mapped_column(String(8))  # low | medium | high
    headline: Mapped[str] = mapped_column(String(160))
    summary: Mapped[str] = mapped_column(String(600))
    suggestion: Mapped[str] = mapped_column(String(300))
    cites: Mapped[str] = mapped_column(String(500), default="")

    # The exact facts the model was given, and their hash: a new push that
    # changes any fact makes the brief stale; an identical one does not.
    facts: Mapped[str] = mapped_column(Text)
    facts_hash: Mapped[str] = mapped_column(String(64))

    generated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
