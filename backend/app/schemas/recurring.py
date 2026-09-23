from __future__ import annotations

from datetime import date
from decimal import Decimal

from pydantic import BaseModel


class RecurringChargeOut(BaseModel):
    counterparty: str
    cadence: str
    occurrences: int
    median_amount: Decimal
    last_amount: Decimal
    last_date: date
    next_expected_date: date
    price_change_pct: float | None
