"""Classified daily state with confidence."""

from __future__ import annotations

import enum
from datetime import date, datetime

from sqlalchemy import Date, DateTime, Enum, Float, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base, utcnow


class LifeState(str, enum.Enum):
    PEAK_PERFORMANCE = "peak_performance"
    EXECUTION_MODE = "execution_mode"
    LEARNING_MODE = "learning_mode"
    RECOVERY_MODE = "recovery_mode"
    BURNOUT_RISK = "burnout_risk"
    AVOIDANCE_MODE = "avoidance_mode"
    EXPLORATION_MODE = "exploration_mode"
    UNKNOWN = "unknown"


class DailyState(Base):
    __tablename__ = "daily_states"
    __table_args__ = (UniqueConstraint("day", name="uq_daily_state_day"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    day: Mapped[date] = mapped_column(Date, index=True)

    state: Mapped[LifeState] = mapped_column(Enum(LifeState, native_enum=False))
    confidence: Mapped[float] = mapped_column(Float)  # 0..1
    rationale: Mapped[str | None] = mapped_column(String(500), nullable=True)

    computed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
