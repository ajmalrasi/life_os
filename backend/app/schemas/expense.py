from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

from pydantic import BaseModel, ConfigDict, Field, field_validator


class TransactionOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    txn_date: date
    # Instant the transaction happened (bank's stated time, else alert arrival
    # time). Null for rows predating the feature — clients render date-only.
    txn_at: datetime | None = None
    amount: Decimal
    currency: str
    direction: str
    counterparty: str | None
    category: str | None
    # Spend-analysis category (labels + keyword rules). ``None`` when the row is
    # excluded from spend (card-bill transfers, reversals, lent-and-returned) so
    # the client can slice by category without double-counting non-spend.
    spend_category: str | None = None
    account: str | None
    source: str
    reference: str | None
    created_at: datetime


class TransactionCategoryUpdate(BaseModel):
    """A null category clears the manual override and restores auto-categorization."""

    category: str | None = Field(default=None, max_length=64)

    @field_validator("category")
    @classmethod
    def normalize_category(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = "_".join(value.strip().lower().replace("-", " ").split())
        if not normalized or not all(ch.isalnum() or ch == "_" for ch in normalized):
            raise ValueError("category must contain only letters, numbers, spaces, hyphens, or underscores")
        return normalized


class RepaymentCandidateOut(BaseModel):
    transaction: TransactionOut
    match_score: int
    reason: str


class RepaymentCreate(BaseModel):
    debit_transaction_id: int
    credit_transaction_id: int


class RepaymentOut(BaseModel):
    id: int
    kind: str
    source: str
    debit: TransactionOut
    credit: TransactionOut
    created_at: datetime
    undone_at: datetime | None = None


class ExpenseSyncResult(BaseModel):
    parsers_run: int
    emails_scanned: int
    transactions_new: int
    transactions_seen: int
    source: str
    message: str
    cursor_epoch: int = 0
    transactions_deferred: int = 0
    new_transactions: list[TransactionOut] = []


class ExpenseRebuildResult(BaseModel):
    since: date
    transactions_deleted: int
    emails_scanned: int
    transactions_new: int
    transactions_seen: int
    labels_created: int
    message: str


class RawEmailFetchResult(BaseModel):
    bank: str
    emails_found: int
    emails_saved: int


class RawEmailProcessResult(BaseModel):
    processed: int
    transactions_created: int
    remaining: int
    stopped_openclaw_unavailable: bool = False


class RawEmailFinalizeResult(BaseModel):
    labels_created: int
    message: str
