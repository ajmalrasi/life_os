"""Singleton row for user-entered money facts income can't be derived from email.

Income never appears in the parsed transaction emails, so the user enters it by
hand; Insights uses it to judge spending against what they actually earn.
"""

from __future__ import annotations

from datetime import datetime
from decimal import Decimal

from sqlalchemy import DateTime, Integer, Numeric
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base, utcnow


class MoneyProfile(Base):
    __tablename__ = "money_profile"

    id: Mapped[int] = mapped_column(primary_key=True)  # always 1 (singleton row)
    monthly_income: Mapped[Decimal | None] = mapped_column(Numeric(14, 2), nullable=True)
    savings_goal_pct: Mapped[int | None] = mapped_column(Integer, nullable=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow, onupdate=utcnow
    )
