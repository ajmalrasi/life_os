"""Budget pace: spent-so-far vs. what that projects to by month-end.

``pace = spent / days_elapsed * days_in_month``. Deliberately the same
straight-line projection Android computes on-device (offline, from its cached
transaction feed) — this module exists so Ask can cite the same numbers
without needing the phone, not to introduce a second formula.
"""

from __future__ import annotations

import calendar
from dataclasses import dataclass
from datetime import date
from decimal import ROUND_CEILING, Decimal
from typing import Callable

from sqlalchemy.orm import Session

from app.models.budget import Budget
from app.repositories.expense_repo import ExpenseRepository
from app.services.reconcile import EXCLUDED_FROM_SPEND


@dataclass
class BudgetPace:
    category: str
    monthly_limit: Decimal
    spent_so_far: Decimal
    projected_month_total: Decimal
    days_elapsed: int
    days_in_month: int
    on_pace_to_exceed: bool
    # Calendar day (1-31) the straight-line pace would cross the limit, if it
    # does within this month; None when spending never catches up to it.
    projected_exceed_day: int | None


def _month_spend_by_category(db: Session, categorize: Callable[[str], str], today: date) -> dict[str, Decimal]:
    month_start = today.replace(day=1)
    txns = ExpenseRepository(db).range(month_start, today)
    totals: dict[str, Decimal] = {}
    for t in txns:
        if t.direction != "debit" or t.category in EXCLUDED_FROM_SPEND:
            continue
        category = t.spend_category_override or categorize(t.counterparty or "")
        totals[category] = totals.get(category, Decimal(0)) + t.amount
    return totals


def budget_pace_summary(
    db: Session,
    categorize: Callable[[str], str],
    today: date | None = None,
) -> list[BudgetPace]:
    """One [BudgetPace] per stored budget, using month-to-date spend as of [today]."""
    today = today or date.today()
    days_in_month = calendar.monthrange(today.year, today.month)[1]
    days_elapsed = today.day  # at least 1 (today.day is 1-indexed)

    spend_by_category = _month_spend_by_category(db, categorize, today)
    budgets = db.query(Budget).order_by(Budget.category).all()

    out: list[BudgetPace] = []
    for budget in budgets:
        spent = spend_by_category.get(budget.category, Decimal(0))
        daily_rate = spent / days_elapsed
        projected = (daily_rate * days_in_month).quantize(Decimal("0.01"))

        exceed_day: int | None = None
        if daily_rate > 0 and budget.monthly_limit > 0:
            day = int((budget.monthly_limit / daily_rate).to_integral_value(rounding=ROUND_CEILING))
            if day <= days_in_month:
                exceed_day = max(day, 1)

        out.append(
            BudgetPace(
                category=budget.category,
                monthly_limit=budget.monthly_limit,
                spent_so_far=spent,
                projected_month_total=projected,
                days_elapsed=days_elapsed,
                days_in_month=days_in_month,
                on_pace_to_exceed=projected > budget.monthly_limit,
                projected_exceed_day=exceed_day,
            )
        )
    return out
