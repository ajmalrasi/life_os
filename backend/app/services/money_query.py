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
import re
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
_KNOWN_CATEGORIES = {
    "fuel": "fuel",
    "wallet": "wallet_load",
    "wallet load": "wallet_load",
    "food": "food",
    "dining": "food",
    "restaurant": "food",
    "groceries": "groceries",
    "grocery": "groceries",
    "shopping": "shopping",
    "subscriptions": "subscriptions",
    "subscription": "subscriptions",
    "investments": "investments",
    "investment": "investments",
    "entertainment": "entertainment",
    "emi": "emi",
    "emi fees": "emi_fees",
    "travel": "travel",
    "legal fees": "legal_fees",
    "internet": "internet_bill",
}


class MoneyQueryError(ValueError):
    """The requested semantic query is invalid or outside the safe contract."""


def plan_money_query(question: str, today: date | None = None) -> dict[str, Any]:
    """Translate common natural-language money phrasing into the safe contract.

    This deterministic planner guarantees that factual Ask questions reach the
    table even when the local model declines an optional function call. The
    model-facing ``query_money`` tool remains available for refinements.
    """
    today = today or date.today()
    text = " ".join(question.casefold().split())
    args: dict[str, Any] = {"mode": "spending"}

    if re.search(r"\b(?:income|earned|earnings|credited|credits?|received)\b", text):
        args["mode"] = "income"
    elif re.search(r"\b(?:debits?\s+and\s+credits?|all\s+transactions?)\b", text):
        args["mode"] = "all"

    start, end = _question_window(text, today)
    args["start_date"] = start.isoformat()
    args["end_date"] = end.isoformat()

    if re.search(r"\b(?:categories|category\s+breakdown|by\s+category)\b", text):
        args["group_by"] = "category"
    elif re.search(r"\b(?:merchants?|payees?|counterpart(?:y|ies))\b", text):
        args["group_by"] = "counterparty"
    elif re.search(r"\b(?:accounts?|cards?|by\s+account|by\s+card)\b", text):
        args["group_by"] = "account"
    elif re.search(r"\b(?:sources?|by\s+source)\b", text):
        args["group_by"] = "source"
    elif re.search(r"\b(?:by\s+month|monthly\s+breakdown)\b", text):
        args["group_by"] = "month"
    elif re.search(r"\b(?:by\s+week|weekly\s+breakdown)\b", text):
        args["group_by"] = "week"
    elif re.search(r"\b(?:by\s+day|daily\s+breakdown)\b", text):
        args["group_by"] = "day"
    else:
        args["group_by"] = "none"

    if args["group_by"] == "none":
        category = _category_from_question(text)
        if category:
            args["category"] = category

    top = re.search(r"\b(?:top|largest|highest|biggest)\s+(\d{1,2})\b", text)
    if top:
        args["limit"] = min(50, max(1, int(top.group(1))))
    elif re.search(r"\b(?:top|largest|highest|biggest|most)\b", text):
        args["limit"] = 5
    else:
        args["limit"] = 10

    if args["group_by"] == "none" and re.search(
        r"\b(?:largest|highest|biggest|top|most expensive)\b", text
    ):
        args["sort_by"] = "amount"
    elif args["group_by"] != "none":
        args["sort_by"] = "total"
    args["sort_order"] = "desc"
    return args


def format_money_answer(question: str, result: dict[str, Any]) -> str | None:
    """Return an instant factual answer; leave interpretive questions to the model."""
    text = question.casefold()
    if re.search(
        r"\b(?:why|driving|cause|affect|relationship|correlat|pattern|compare|versus|vs)\b",
        text,
    ):
        return None

    summary = result["summary"]
    window = result["window"]
    mode = result["mode"]
    noun = {"spending": "spending", "income": "income", "all": "transaction value"}[mode]
    period = f"{window['start']} to {window['end']}"
    filters = result.get("filters") or {}

    if not summary["count"]:
        qualifier = ""
        if filters.get("category"):
            qualifier = f" in {str(filters['category']).replace('_', ' ')}"
        return f"I found no matching {noun}{qualifier} from {period}."

    if result["group_by"] != "none":
        dimension = result["group_by"].replace("counterparty", "merchant")
        lines = [f"From {period}, your top {noun} by {dimension} was:"]
        for index, row in enumerate(result["rows"], start=1):
            group = str(row["group"]).replace("_", " ")
            lines.append(
                f"{index}. {group}: {_inr(row['total'])} across "
                f"{row['count']} transaction{'s' if row['count'] != 1 else ''}"
            )
        lines.append(
            f"Matched total: {_inr(summary['total'])} across "
            f"{summary['count']} transactions."
        )
        return "\n".join(lines)

    qualifier = ""
    if filters.get("category"):
        qualifier = f" in {str(filters['category']).replace('_', ' ')}"
    return (
        f"From {period}, your matched {noun}{qualifier} was "
        f"{_inr(summary['total'])} across {summary['count']} "
        f"transaction{'s' if summary['count'] != 1 else ''}. "
        f"The largest was {_inr(summary['max'])}."
    )


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


def _question_window(text: str, today: date) -> tuple[date, date]:
    if "last month" in text:
        this_month = today.replace(day=1)
        end = this_month - timedelta(days=1)
        return end.replace(day=1), end
    if re.search(r"\b(?:this|current) month\b", text):
        return today.replace(day=1), today
    if "yesterday" in text:
        yesterday = today - timedelta(days=1)
        return yesterday, yesterday
    if re.search(r"\btoday\b", text):
        return today, today
    if "last week" in text:
        this_week = today - timedelta(days=today.weekday())
        end = this_week - timedelta(days=1)
        return end - timedelta(days=6), end
    if "this week" in text:
        return today - timedelta(days=today.weekday()), today
    if "last year" in text:
        year = today.year - 1
        return date(year, 1, 1), date(year, 12, 31)
    if "this year" in text:
        return date(today.year, 1, 1), today

    trailing = re.search(r"\b(?:last|past)\s+(\d{1,4})\s+(days?|weeks?|months?)\b", text)
    if trailing:
        count = max(1, int(trailing.group(1)))
        unit = trailing.group(2)
        if unit.startswith("week"):
            days = count * 7
        elif unit.startswith("month"):
            days = count * 31
        else:
            days = count
        return today - timedelta(days=min(days, 3650) - 1), today

    month_names = {
        "january": 1, "jan": 1, "february": 2, "feb": 2, "march": 3, "mar": 3,
        "april": 4, "apr": 4, "may": 5, "june": 6, "jun": 6, "july": 7, "jul": 7,
        "august": 8, "aug": 8, "september": 9, "sep": 9, "october": 10, "oct": 10,
        "november": 11, "nov": 11, "december": 12, "dec": 12,
    }
    month_match = re.search(
        r"\b(" + "|".join(month_names) + r")(?:\s+(20\d{2}))?\b",
        text,
    )
    if month_match:
        month = month_names[month_match.group(1)]
        year = int(month_match.group(2) or today.year)
        start = date(year, month, 1)
        next_month = date(year + (month == 12), 1 if month == 12 else month + 1, 1)
        end = min(next_month - timedelta(days=1), today) if start <= today else next_month - timedelta(days=1)
        return start, end

    return today - timedelta(days=_DEFAULT_DAYS - 1), today


def _category_from_question(text: str) -> str | None:
    for phrase in sorted(_KNOWN_CATEGORIES, key=len, reverse=True):
        if re.search(rf"\b{re.escape(phrase)}\b", text):
            return _KNOWN_CATEGORIES[phrase]
    return None


def _inr(value: int | float) -> str:
    number = float(value)
    return f"₹{number:,.0f}" if number.is_integer() else f"₹{number:,.2f}"
