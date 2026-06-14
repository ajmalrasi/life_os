"""Daily snapshot of derived scores.

These are the engineered features the correlation/pattern engine operates on.
Recomputed from raw data, so they can be regenerated at any time.
"""

from __future__ import annotations

from datetime import date, datetime

from sqlalchemy import Date, DateTime, Float, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


class DailyScore(Base):
    __tablename__ = "daily_scores"
    __table_args__ = (UniqueConstraint("day", name="uq_daily_score_day"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    day: Mapped[date] = mapped_column(Date, index=True)

    # Phase 1 scores (0-100). Nullable when source data is missing for the day.
    sleep_score: Mapped[float | None] = mapped_column(Float, nullable=True)
    recovery_score: Mapped[float | None] = mapped_column(Float, nullable=True)
    execution_score: Mapped[float | None] = mapped_column(Float, nullable=True)
    consistency_score: Mapped[float | None] = mapped_column(Float, nullable=True)
    burnout_risk: Mapped[float | None] = mapped_column(Float, nullable=True)

    computed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow)
