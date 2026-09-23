from __future__ import annotations

from decimal import Decimal

from pydantic import BaseModel, Field


class BudgetIn(BaseModel):
    monthly_limit: Decimal = Field(..., gt=0)


class BudgetPaceOut(BaseModel):
    category: str
    monthly_limit: Decimal
    spent_so_far: Decimal
    projected_month_total: Decimal
    days_elapsed: int
    days_in_month: int
    on_pace_to_exceed: bool
    projected_exceed_day: int | None
