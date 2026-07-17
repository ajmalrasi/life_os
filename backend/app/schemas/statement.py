from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

from pydantic import BaseModel, ConfigDict


class CardStatementOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    bank: str
    card_label: str
    card_last4: str | None
    statement_date: date | None
    period_start: date | None
    period_end: date | None
    due_date: date | None
    total_due: Decimal | None
    min_due: Decimal | None
    total_spend: Decimal | None
    line_items_total: int
    line_items_ingested: int
    created_at: datetime
    paid_at: datetime | None = None


class StatementPaidUpdate(BaseModel):
    paid: bool


class StatementSyncResult(BaseModel):
    banks_run: int
    emails_seen: int
    statements_new: int
    line_items_ingested: int
    line_items_deduped: int
    message: str
    statements: list[CardStatementOut] = []


class CategorySpend(BaseModel):
    category: str
    total: Decimal
    count: int


class MonthTrend(BaseModel):
    """One calendar month of stored transactions (live-alert sourced going forward)."""

    month: str  # "2026-06"
    total_debits: Decimal
    total_credits: Decimal
    txn_count: int
    by_category: list[CategorySpend]
    by_account: list[CategorySpend]


class SpendTrends(BaseModel):
    months: list[MonthTrend]
    aggregate_debits: Decimal
    aggregate_by_category: list[CategorySpend]


class SpendAnalysis(BaseModel):
    """Month-at-a-glance across all cards + the live UPI feed."""

    total_due_all_cards: Decimal
    min_due_all_cards: Decimal
    next_due_date: date | None
    cards: list[CardStatementOut]
    spend_by_category: list[CategorySpend]
    top_merchants: list[CategorySpend]
