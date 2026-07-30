"""Exact tracked spend inside each credit card's active billing cycle.

Calendar-month spend and card billing cycles answer different questions. The
latest parsed statements supply the exact per-card cycle cadence; live alert
transactions supply only spending that has already happened. Nothing here
forecasts EMIs, subscriptions, fees, payment dates, or a future bill total.
"""

from __future__ import annotations

import calendar
import re
from collections import defaultdict
from datetime import date, timedelta
from decimal import Decimal

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.account_identity import normalize_account
from app.models.card_statement import CardStatement
from app.models.transaction import Transaction
from app.repositories.expense_repo import ExpenseRepository
from app.schemas.statement import CurrentCardCycleOut, CurrentCardCyclesOut
from app.services.reconcile import EXCLUDED_FROM_SPEND

_TRAILING_DIGITS = re.compile(r"\.\.\s*(\d+)\s*$")


def _is_month_end(value: date) -> bool:
    return value.day == calendar.monthrange(value.year, value.month)[1]


def _next_month(value: date, previous: date | None = None) -> date:
    """Advance one calendar month while preserving an end-of-month cadence."""
    year = value.year + (1 if value.month == 12 else 0)
    month = 1 if value.month == 12 else value.month + 1
    last_day = calendar.monthrange(year, month)[1]
    end_anchored = _is_month_end(value) and (previous is None or _is_month_end(previous))
    day = last_day if end_anchored else min(value.day, last_day)
    return date(year, month, day)


def _account_matches(account: str | None, bank: str, last4: str) -> bool:
    if not account:
        return False
    parts = account.split()
    if not parts or parts[0].casefold() != bank.casefold():
        return False
    match = _TRAILING_DIGITS.search(account)
    if not match:
        return False
    digits = match.group(1)
    return digits.endswith(last4) or last4.endswith(digits)


def _canonical_last4(bank: str, last4: str) -> str:
    """Expand a known short statement suffix such as SBI ``70`` to ``1970``."""
    normalized = normalize_account(f"{bank} Card ..{last4}") or ""
    match = _TRAILING_DIGITS.search(normalized)
    return match.group(1) if match else last4


def _is_cycle_activity(transaction: Transaction) -> bool:
    return transaction.category not in EXCLUDED_FROM_SPEND


def current_card_cycles(db: Session, as_of: date | None = None) -> CurrentCardCyclesOut:
    as_of = as_of or date.today()
    statements = list(
        db.execute(
            select(CardStatement).order_by(
                CardStatement.bank,
                CardStatement.card_last4,
                CardStatement.statement_date.desc().nullslast(),
                CardStatement.id.desc(),
            )
        ).scalars()
    )

    histories: dict[tuple[str, str], list[CardStatement]] = defaultdict(list)
    for statement in statements:
        if statement.card_last4 and statement.period_end:
            histories[(statement.bank.casefold(), statement.card_last4)].append(statement)

    if not histories:
        return CurrentCardCyclesOut(
            as_of=as_of,
            current_spend_total=Decimal(0),
            refund_total=Decimal(0),
            net_cycle_total=Decimal(0),
            cycles=[],
        )

    earliest_cycle_start = min(
        history[0].period_end + timedelta(days=1) for history in histories.values()
    )
    transactions = list(
        db.execute(
            select(Transaction).where(
                Transaction.txn_date >= earliest_cycle_start,
                Transaction.txn_date <= as_of,
                Transaction.source.in_(ExpenseRepository.LIVE_SOURCES),
            )
        ).scalars()
    )

    cycles: list[CurrentCardCycleOut] = []
    for (_bank_key, last4), history in histories.items():
        latest = history[0]
        previous = history[1] if len(history) > 1 else None

        cycle_start = latest.period_end + timedelta(days=1)
        cycle_end = _next_month(latest.period_end, previous.period_end if previous else None)

        # If statement ingestion missed one or more months, move to the exact
        # cadence window containing today instead of accumulating old cycles.
        previous_end = latest.period_end
        guard = 0
        while cycle_end < as_of and guard < 24:
            previous_cycle_end = previous_end
            previous_end = cycle_end
            cycle_start = cycle_end + timedelta(days=1)
            cycle_end = _next_month(cycle_end, previous_cycle_end)
            guard += 1

        current = [
            transaction
            for transaction in transactions
            if cycle_start <= transaction.txn_date <= as_of
            and _account_matches(transaction.account, latest.bank, last4)
            and _is_cycle_activity(transaction)
        ]

        current_spend = Decimal(0)
        refunds = Decimal(0)
        included_count = 0
        unconverted_count = 0
        last_transaction_date = None
        for transaction in current:
            if transaction.currency != "INR":
                unconverted_count += 1
                continue
            if transaction.direction == "debit":
                current_spend += transaction.amount
            elif transaction.direction == "credit":
                refunds += transaction.amount
            else:
                continue
            included_count += 1
            if last_transaction_date is None or transaction.txn_date > last_transaction_date:
                last_transaction_date = transaction.txn_date

        cycles.append(
            CurrentCardCycleOut(
                bank=latest.bank,
                card_label=latest.card_label,
                card_last4=_canonical_last4(latest.bank, last4),
                cycle_start=cycle_start,
                cycle_end=cycle_end,
                current_spend=current_spend,
                refunds=refunds,
                net_cycle_amount=current_spend - refunds,
                transaction_count=included_count,
                last_transaction_date=last_transaction_date,
                unconverted_transaction_count=unconverted_count,
            )
        )

    cycles.sort(key=lambda cycle: (cycle.cycle_end, cycle.bank, cycle.card_last4))
    current_spend_total = sum((cycle.current_spend for cycle in cycles), Decimal(0))
    refund_total = sum((cycle.refunds for cycle in cycles), Decimal(0))
    return CurrentCardCyclesOut(
        as_of=as_of,
        current_spend_total=current_spend_total,
        refund_total=refund_total,
        net_cycle_total=current_spend_total - refund_total,
        cycles=cycles,
    )


def current_card_cycle_details(
    db: Session,
    bank: str,
    card_last4: str,
    as_of: date | None = None,
) -> tuple[CurrentCardCycleOut, list[Transaction]] | None:
    """Return one exact cycle plus only the rows included in its totals."""
    summary = current_card_cycles(db, as_of=as_of)
    cycle = next(
        (
            item
            for item in summary.cycles
            if item.bank.casefold() == bank.casefold()
            and (
                item.card_last4.endswith(card_last4)
                or card_last4.endswith(item.card_last4)
            )
        ),
        None,
    )
    if cycle is None:
        return None

    rows = list(
        db.execute(
            select(Transaction)
            .where(
                Transaction.txn_date >= cycle.cycle_start,
                Transaction.txn_date <= summary.as_of,
                Transaction.source.in_(ExpenseRepository.LIVE_SOURCES),
                Transaction.currency == "INR",
                Transaction.direction.in_(("debit", "credit")),
            )
            .order_by(Transaction.txn_date.desc(), Transaction.id.desc())
        ).scalars()
    )
    included = [
        transaction
        for transaction in rows
        if _account_matches(transaction.account, cycle.bank, cycle.card_last4)
        and _is_cycle_activity(transaction)
    ]
    return cycle, included
