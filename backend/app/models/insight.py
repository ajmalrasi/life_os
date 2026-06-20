"""Discovered patterns / correlations surfaced as human-readable insights."""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, Float, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base, utcnow


class Insight(Base):
    __tablename__ = "insights"
    __table_args__ = (
        # One row per (driver, outcome) pair; nightly run upserts it.
        UniqueConstraint("driver", "outcome", name="uq_insight_pair"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)

    driver: Mapped[str] = mapped_column(String(64))   # e.g. "sleep_score"
    outcome: Mapped[str] = mapped_column(String(64))  # e.g. "execution_score"

    correlation: Mapped[float] = mapped_column(Float)  # Pearson r, -1..1
    sample_size: Mapped[int] = mapped_column(Integer)
    lag_days: Mapped[int] = mapped_column(Integer, default=0)

    # Human-readable summary, e.g. "Productivity increases 31% when sleep exceeds 7.2 hours."
    summary: Mapped[str] = mapped_column(String(500))

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow, onupdate=utcnow
    )
