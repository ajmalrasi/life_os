"""Daily subjective mood / energy check-in."""

from __future__ import annotations

from datetime import date, datetime

from sqlalchemy import Date, DateTime, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base, utcnow


class MoodCheckin(Base):
    __tablename__ = "mood_checkins"
    __table_args__ = (UniqueConstraint("day", name="uq_mood_day"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    day: Mapped[date] = mapped_column(Date, index=True)

    # 1-5 Likert scales — the subjective ground truth the engine correlates against.
    mood: Mapped[int] = mapped_column(Integer)       # 1 (low) .. 5 (great)
    energy: Mapped[int] = mapped_column(Integer)     # 1 .. 5
    focus: Mapped[int] = mapped_column(Integer)      # 1 .. 5
    stress: Mapped[int] = mapped_column(Integer)     # 1 (calm) .. 5 (stressed)

    note: Mapped[str | None] = mapped_column(String(1000), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
