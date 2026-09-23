"""Monthly spend budgets, one row per category (Stage 4b).

Backend only stores the limit; pace (spent so far vs. what that projects to by
month-end) is computed on read here and, separately, on-device in Android from
the cached transaction feed so the budget bars work offline. Both sides use
the same effective-category logic as `build_categorizer`
(app/api/routes/statements.py) so a category here means the same thing it
means on the Money page.
"""

from __future__ import annotations

from datetime import datetime
from decimal import Decimal

from sqlalchemy import DateTime, Numeric, String
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base, utcnow


class Budget(Base):
    __tablename__ = "budgets"

    category: Mapped[str] = mapped_column(String(64), primary_key=True)
    monthly_limit: Mapped[Decimal] = mapped_column(Numeric(14, 2))
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow, onupdate=utcnow
    )
