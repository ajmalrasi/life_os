from __future__ import annotations

from decimal import Decimal

from pydantic import BaseModel, Field


class MoneyProfileIn(BaseModel):
    monthly_income: Decimal | None = Field(None, gt=0, le=10_000_000)
    savings_goal_pct: int | None = Field(None, ge=0, le=90)


class MoneyProfileOut(BaseModel):
    monthly_income: Decimal | None
    savings_goal_pct: int | None
    updated_at: str | None
