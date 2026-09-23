"""Weekly recap: a short narrated summary of the trailing 7 days.

Unlike ``synthesized_insights`` (wiped and regenerated on every nightly run),
one row here is written once per week by a dedicated systemd timer and kept —
so a recap survives the next several nightly recomputes instead of vanishing
the moment daily analytics runs again.
"""

from __future__ import annotations

from datetime import date, datetime

from sqlalchemy import Date, DateTime, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base, utcnow


class WeeklyRecap(Base):
    __tablename__ = "weekly_recaps"
    __table_args__ = (UniqueConstraint("week_start", name="uq_weekly_recap_week_start"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    week_start: Mapped[date] = mapped_column(Date)
    week_end: Mapped[date] = mapped_column(Date)

    # Free-form recap prose. LLM-written when available, otherwise the
    # deterministic fallback built straight from the computed numbers — never
    # empty, since the numbers are always real data.
    prose: Mapped[str] = mapped_column(Text)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
