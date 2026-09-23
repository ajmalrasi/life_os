"""Recurring-charge detection: groups debits by normalized counterparty and
flags ones that repeat on a roughly weekly or monthly cadence at a roughly
stable amount — subscriptions, EMIs, memberships, and the like.

Detection only. Per the 2026-07-30 decision (AGENTS.md §6, "Exact open
credit-card cycle spend"), LifeOS deliberately never forecasts unrecorded
charges into card-cycle or budget totals; this only reports what's already
been observed and when the *next* one is expected, for display.
"""

from __future__ import annotations

import re
import statistics
from dataclasses import dataclass
from datetime import date, timedelta
from decimal import Decimal

from sqlalchemy.orm import Session

from app.repositories.expense_repo import ExpenseRepository
from app.services.reconcile import EXCLUDED_FROM_SPEND

MIN_OCCURRENCES = 3
AMOUNT_TOLERANCE = 0.10  # each occurrence must be within +/-10% of the median
GAP_TOLERANCE_DAYS = 4  # each gap must be within +/-4 days of the median gap
WEEKLY_GAP_RANGE = (3, 11)  # median gap (days) consistent with "weekly"
MONTHLY_GAP_RANGE = (26, 34)  # median gap (days) consistent with "monthly"
DEFAULT_WINDOW_DAYS = 180

_WHITESPACE = re.compile(r"\s+")
_LONG_DIGIT_RUN = re.compile(r"\d{4,}")  # strip likely reference/txn numbers


def normalize_counterparty(raw: str) -> str:
    """Collapse whitespace/case and drop long digit runs (transaction
    reference numbers) so the same merchant groups together even when each
    alert embeds a different reference id."""
    text = _LONG_DIGIT_RUN.sub("", raw or "")
    text = _WHITESPACE.sub(" ", text).strip().lower()
    return text


@dataclass
class RecurringCharge:
    counterparty: str  # original (non-normalized) label, most recent occurrence
    cadence: str  # "weekly" | "monthly"
    occurrences: int
    median_amount: Decimal
    last_amount: Decimal
    last_date: date
    next_expected_date: date
    # Positive = the last charge was more expensive than the typical one.
    price_change_pct: float | None


def _cadence_for_gap(median_gap: float) -> str | None:
    if WEEKLY_GAP_RANGE[0] <= median_gap <= WEEKLY_GAP_RANGE[1]:
        return "weekly"
    if MONTHLY_GAP_RANGE[0] <= median_gap <= MONTHLY_GAP_RANGE[1]:
        return "monthly"
    return None


def _detect_one(counterparty_label: str, rows: list) -> RecurringCharge | None:
    if len(rows) < MIN_OCCURRENCES:
        return None
    rows = sorted(rows, key=lambda t: t.txn_date)
    dates = [t.txn_date for t in rows]
    amounts = [t.amount for t in rows]

    gaps = [(b - a).days for a, b in zip(dates, dates[1:])]
    median_gap = statistics.median(gaps)
    cadence = _cadence_for_gap(median_gap)
    if cadence is None:
        return None
    if any(abs(g - median_gap) > GAP_TOLERANCE_DAYS for g in gaps):
        return None

    median_amount = statistics.median(amounts)
    if median_amount <= 0:
        return None
    if any(abs(float(a) - float(median_amount)) / float(median_amount) > AMOUNT_TOLERANCE for a in amounts):
        return None

    last_date = dates[-1]
    last_amount = amounts[-1]
    price_change_pct = (
        round((float(last_amount) - float(median_amount)) / float(median_amount) * 100, 1)
        if median_amount > 0
        else None
    )

    return RecurringCharge(
        counterparty=rows[-1].counterparty or counterparty_label,
        cadence=cadence,
        occurrences=len(rows),
        median_amount=Decimal(str(median_amount)),
        last_amount=last_amount,
        last_date=last_date,
        next_expected_date=last_date + timedelta(days=round(median_gap)),
        price_change_pct=price_change_pct,
    )


def detect_recurring_charges(
    db: Session,
    window_days: int = DEFAULT_WINDOW_DAYS,
    today: date | None = None,
) -> list[RecurringCharge]:
    today = today or date.today()
    start = today - timedelta(days=window_days)
    rows = ExpenseRepository(db).range(start, today)

    groups: dict[str, list] = {}
    for t in rows:
        if t.direction != "debit" or t.category in EXCLUDED_FROM_SPEND:
            continue
        if not t.counterparty:
            continue
        key = normalize_counterparty(t.counterparty)
        if not key:
            continue
        groups.setdefault(key, []).append(t)

    out = [charge for key, group in groups.items() if (charge := _detect_one(key, group)) is not None]
    out.sort(key=lambda c: c.last_date, reverse=True)
    return out
