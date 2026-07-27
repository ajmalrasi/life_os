"""Guarded analytical queries over the stored transaction table.

The Ask model translates natural language into the small query contract in this
module.  Application code, not model-authored SQL, builds the SQLAlchemy query.
That keeps the capability read-only and prevents prompt injection from reaching
arbitrary tables, columns, SQL functions, or write statements.
"""

from __future__ import annotations

from collections import defaultdict
from datetime import date, timedelta
from decimal import Decimal
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.api.routes.statements import build_categorizer
from app.models.transaction import Transaction
from app.services.reconcile import EXCLUDED_FROM_SPEND

_MODES = {"spending", "income", "all"}
_GROUPS = {
    "none",
    "day",
    "week",
    "month",
    "category",
    "counterparty",
    "account",
    "source",
    "direction",
}
_SORTS = {"total", "count", "average", "max", "date", "amount", "name"}
_ORDERS = {"asc", "desc"}
_MAX_RESULT_ROWS = 50
_DEFAULT_DAYS = 30


class MoneyQueryError(ValueError):
    """The requested semantic query is invalid or outside the safe contract."""


def spending_coverage(db: Session) -> dict[str, Any] | None:
    """Return compact coverage metadata for genuine stored debit spending."""
    count, first, last = db.execute(
        select(
            func.count(Transaction.id),
            func.min(Transaction.txn_date),
            func.max(Transaction.txn_date),
        ).where(
            Transaction.direction == "debit",
            func.coalesce(Transaction.category, "").notin_(tuple(EXCLUDED_FROM_SPEND)),
        )
    ).one()
    if not count:
        return None
    return {
        "transaction_count": int(count),
        "first_date": first.isoformat(),
        "last_date": last.isoformat(),
    }


def query_money(
    db: Session,
    *,
    mode: str = "spending",
    start_date: str | None = None,
    end_date: str | None = None,
    group_by: str = "none",
    category: str | None = None,
    counterparty_contains: str | None = None,
    account_contains: str | None = None,
    source: str | None = None,
    min_amount: float | int | str | None = None,
    max_amount: float | int | str | None = None,
    sort_by: str | None = None,
    sort_order: str = "desc",
    limit: int = 10,
) -> dict[str, Any]:
    """Run a read-only, allowlisted transaction query and return compact facts."""
    mode = _enum(mode, _MODES, "mode")
    group_by = _enum(group_by, _GROUPS, "group_by")
    sort_order = _enum(sort_order, _ORDERS, "sort_order")
    sort_by = _enum(
        sort_by or ("date" if group_by == "none" else "total"),
        _SORTS,
        "sort_by",
    )
    limit = _bounded_int(limit, 1, _MAX_RESULT_ROWS, "limit")

    today = date.today()
    end = _parse_date(end_date, "end_date") if end_date else today
    start = (
        _parse_date(start_date, "start_date")
        if start_date
        else end - timedelta(days=_DEFAULT_DAYS - 1)
    )
    if start > end:
        raise MoneyQueryError("start_date must be on or before end_date")

    minimum = _decimal(min_amount, "min_amount")
    maximum = _decimal(max_amount, "max_amount")
    if minimum is not None and maximum is not None and minimum > maximum:
        raise MoneyQueryError("min_amount must be less than or equal to max_amount")

    stmt = select(Transaction).where(
        Transaction.txn_date >= start,
        Transaction.txn_date <= end,
    )
    if mode == "spending":
        stmt = stmt.where(
            Transaction.direction == "debit",
            func.coalesce(Transaction.category, "").notin_(tuple(EXCLUDED_FROM_SPEND)),
        )
    elif mode == "income":
        stmt = stmt.where(
            Transaction.direction == "credit",
            func.coalesce(Transaction.category, "").notin_(tuple(EXCLUDED_FROM_SPEND)),
        )
    if minimum is not None:
        stmt = stmt.where(Transaction.amount >= minimum)
    if maximum is not None:
        stmt = stmt.where(Transaction.amount <= maximum)
    if counterparty_contains:
        stmt = stmt.where(Transaction.counterparty.ilike(f"%{_text(counterparty_contains)}%"))
    if account_contains:
        stmt = stmt.where(Transaction.account.ilike(f"%{_text(account_contains)}%"))
    if source:
        stmt = stmt.where(Transaction.source == _text(source))

    # The DB performs all safe scalar filtering. Effective categories depend on
    # user-authored payee rules plus deterministic regexes, so that final filter
    # and grouping intentionally happen in Python using the same categorizer as
    # the Money API. This prevents Ask and the app from disagreeing.
    transactions = list(db.execute(stmt).scalars())
    categorize = build_categorizer(db)
    prepared = [
        (txn, _effective_category(txn, categorize))
        for txn in transactions
    ]
    if category:
        wanted = _text(category).casefold()
        prepared = [
            (txn, effective)
            for txn, effective in prepared
            if (effective or "").casefold() == wanted
        ]

    amounts = [txn.amount for txn, _ in prepared]
    response: dict[str, Any] = {
        "dataset": "transactions",
        "mode": mode,
        "window": {"start": start.isoformat(), "end": end.isoformat()},
        "filters": {
            key: value
            for key, value in {
                "category": category,
                "counterparty_contains": counterparty_contains,
                "account_contains": account_contains,
                "source": source,
                "min_amount": _number(minimum),
                "max_amount": _number(maximum),
            }.items()
            if value is not None
        },
        "summary": _summary(amounts),
        "group_by": group_by,
    }

    if group_by == "none":
        rows = [
            {
                "date": txn.txn_date.isoformat(),
                "amount": _number(txn.amount),
                "currency": txn.currency,
                "direction": txn.direction,
                "category": effective,
                "counterparty": txn.counterparty,
                "account": txn.account,
                "source": txn.source,
            }
            for txn, effective in prepared
        ]
        rows.sort(
            key=_transaction_sort_key(sort_by),
            reverse=sort_order == "desc",
        )
        response["rows"] = rows[:limit]
    else:
        grouped: dict[str, list[Decimal]] = defaultdict(list)
        for txn, effective in prepared:
            grouped[_group_key(txn, effective, group_by)].append(txn.amount)
        rows = [
            {"group": key, **_summary(values)}
            for key, values in grouped.items()
        ]
        rows.sort(
            key=_group_sort_key(sort_by),
            reverse=sort_order == "desc",
        )
        response["rows"] = rows[:limit]

    response["returned_rows"] = len(response["rows"])
    response["matched_transactions"] = len(prepared)
    return response


def _effective_category(txn: Transaction, categorize) -> str | None:
    if txn.category in EXCLUDED_FROM_SPEND:
        return None
    return txn.spend_category_override or categorize(txn.counterparty or "")


def _group_key(txn: Transaction, category: str | None, group_by: str) -> str:
    if group_by == "day":
        return txn.txn_date.isoformat()
    if group_by == "week":
        return (txn.txn_date - timedelta(days=txn.txn_date.weekday())).isoformat()
    if group_by == "month":
        return txn.txn_date.strftime("%Y-%m")
    if group_by == "category":
        return category or "uncategorized"
    value = getattr(txn, group_by)
    return str(value) if value else "unknown"


def _summary(amounts: list[Decimal]) -> dict[str, int | float]:
    if not amounts:
        return {
            "total": 0,
            "count": 0,
            "average": 0,
            "max": 0,
        }
    total = sum(amounts, Decimal("0"))
    return {
        "total": _number(total),
        "count": len(amounts),
        "average": _number(total / len(amounts)),
        "max": _number(max(amounts)),
    }


def _transaction_sort_key(sort_by: str):
    if sort_by in {"amount", "total", "average", "max"}:
        return lambda row: (row["amount"], row["date"])
    if sort_by == "name":
        return lambda row: ((row["counterparty"] or "").casefold(), row["date"])
    # count is not meaningful for individual rows; date is the safest fallback.
    return lambda row: (row["date"], row["amount"])


def _group_sort_key(sort_by: str):
    if sort_by in {"total", "count", "average", "max"}:
        return lambda row: (row[sort_by], row["group"])
    return lambda row: row["group"]


def _number(value: Decimal | None) -> int | float | None:
    if value is None:
        return None
    rounded = value.quantize(Decimal("0.01"))
    return int(rounded) if rounded == rounded.to_integral() else float(rounded)


def _parse_date(raw: str, field: str) -> date:
    try:
        return date.fromisoformat(str(raw))
    except (TypeError, ValueError) as exc:
        raise MoneyQueryError(f"{field} must be an ISO date (YYYY-MM-DD)") from exc


def _decimal(raw: float | int | str | None, field: str) -> Decimal | None:
    if raw is None:
        return None
    try:
        value = Decimal(str(raw))
    except Exception as exc:
        raise MoneyQueryError(f"{field} must be a number") from exc
    if not value.is_finite() or value < 0:
        raise MoneyQueryError(f"{field} must be a finite non-negative number")
    return value


def _enum(raw: str, allowed: set[str], field: str) -> str:
    value = str(raw).casefold()
    if value not in allowed:
        raise MoneyQueryError(f"{field} must be one of: {', '.join(sorted(allowed))}")
    return value


def _bounded_int(raw: int, minimum: int, maximum: int, field: str) -> int:
    try:
        value = int(raw)
    except (TypeError, ValueError) as exc:
        raise MoneyQueryError(f"{field} must be an integer") from exc
    if not minimum <= value <= maximum:
        raise MoneyQueryError(f"{field} must be between {minimum} and {maximum}")
    return value


def _text(raw: str) -> str:
    value = str(raw).strip()
    if not value or len(value) > 100:
        raise MoneyQueryError("text filters must contain 1-100 characters")
    return value
