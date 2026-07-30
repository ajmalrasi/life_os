from __future__ import annotations

from datetime import date, datetime, time
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
    status: str
    details_status: str
    details_parser_version: int
    details_parsed_at: datetime | None = None


class CardStatementItemOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    sequence: int
    txn_date: date
    txn_time: time | None = None
    description: str
    amount: Decimal
    direction: str
    item_type: str


class StatementBreakdownItem(BaseModel):
    key: str
    label: str
    total: Decimal
    count: int


class CardStatementDetailsOut(BaseModel):
    statement: CardStatementOut
    items: list[CardStatementItemOut]
    breakdown: list[StatementBreakdownItem]


class ExpectedEmiOut(BaseModel):
    reference: str
    next_installment: int
    total_installments: int
    estimated_amount: Decimal


class CurrentCardCycleOut(BaseModel):
    bank: str
    card_label: str
    card_last4: str
    cycle_start: date
    cycle_end: date
    expected_statement_date: date | None
    expected_due_date: date | None
    posted_debits: Decimal
    posted_credits: Decimal
    posted_total: Decimal
    expected_emi_total: Decimal
    estimated_total: Decimal
    expected_emis: list[ExpectedEmiOut]
    transaction_count: int
    last_transaction_date: date | None
    historical_coverage: Decimal | None
    confidence: str
    unconverted_transaction_count: int = 0


class CurrentCardCyclesOut(BaseModel):
    as_of: date
    posted_total: Decimal
    expected_emi_total: Decimal
    estimated_total: Decimal
    cycles: list[CurrentCardCycleOut]


class StatementPaidUpdate(BaseModel):
    paid: bool


class StatementSyncResult(BaseModel):
    banks_run: int
    emails_seen: int
    statements_new: int
    line_items_ingested: int
    line_items_stored: int = 0
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
