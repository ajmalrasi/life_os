"""Live credit-card cycle estimates built from statements plus alert transactions.

Calendar-month spend and card billing cycles answer different questions. This
service keeps that accounting boundary explicit:

* the latest parsed statement supplies each physical card's cycle cadence;
* live alert rows supply charges/refunds posted after that statement closed;
* only explicit ``N/M`` EMI rows are forecast into the next cycle;
* the result remains an estimate until the next statement PDF replaces it.
"""

from __future__ import annotations

import calendar
import re
from collections import defaultdict
from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_UP

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.card_statement import CardStatement, CardStatementItem
from app.models.transaction import Transaction
from app.repositories.expense_repo import ExpenseRepository
from app.schemas.statement import CurrentCardCycleOut, CurrentCardCyclesOut, ExpectedEmiOut
from app.services.reconcile import EXCLUDED_FROM_SPEND

_TRAILING_DIGITS = re.compile(r"\.\.\s*(\d+)\s*$")
_EMI_COMPONENT = re.compile(
    r"\bEMI\s+(PRINCIPAL|INTEREST)\s*-\s*(\d+)\s*/\s*(\d+)"
    r".*?\bREF#\s*([A-Z0-9-]+)",
    re.I,
)
_MONEY = Decimal("0.01")
_GST_RATE = Decimal("0.18")


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


def _included_amount(transaction: Transaction) -> Decimal | None:
    """Signed INR amount contributing to a not-yet-generated card bill.

    Card payments/self-transfers and reconciled movements do not change new
    cycle spending. Paired reversals are excluded on both legs; an ordinary
    unclassified card credit remains a genuine refund and reduces the estimate.
    """
    if transaction.currency != "INR" or transaction.category in EXCLUDED_FROM_SPEND:
        return None
    if transaction.direction == "debit":
        return transaction.amount
    if transaction.direction == "credit":
        return -transaction.amount
    return None


def _explicit_expected_emis(items: list[CardStatementItem]) -> list[ExpectedEmiOut]:
    """Forecast only installments whose statement text proves another is due.

    Axis prints principal and interest as separate ``N/M`` lines. Their sum is
    the installment; GST on interest is estimated at 18%. Descriptions without
    an explicit sequence are intentionally ignored rather than guessed.
    """
    grouped: dict[str, dict[str, Decimal | int]] = {}
    for item in items:
        match = _EMI_COMPONENT.search(item.description)
        if not match or item.direction != "debit":
            continue
        component, current_raw, total_raw, reference = match.groups()
        current = int(current_raw)
        total = int(total_raw)
        if current >= total:
            continue
        entry = grouped.setdefault(
            reference.upper(),
            {
                "current": current,
                "total": total,
                "principal": Decimal(0),
                "interest": Decimal(0),
            },
        )
        # Inconsistent rows for one reference are safer to ignore than merge.
        if entry["current"] != current or entry["total"] != total:
            entry["invalid"] = 1
            continue
        entry[component.casefold()] = Decimal(entry[component.casefold()]) + item.amount

    expected: list[ExpectedEmiOut] = []
    for reference, entry in grouped.items():
        if entry.get("invalid"):
            continue
        principal = Decimal(entry["principal"])
        interest = Decimal(entry["interest"])
        amount = (principal + interest + interest * _GST_RATE).quantize(
            _MONEY, rounding=ROUND_HALF_UP
        )
        if amount <= 0:
            continue
        expected.append(
            ExpectedEmiOut(
                reference=reference,
                next_installment=int(entry["current"]) + 1,
                total_installments=int(entry["total"]),
                estimated_amount=amount,
            )
        )
    return sorted(expected, key=lambda item: item.reference)


def _confidence(coverage: Decimal | None) -> str:
    if coverage is None:
        return "unknown"
    if coverage >= Decimal("0.90"):
        return "high"
    if coverage >= Decimal("0.60"):
        return "medium"
    return "partial"


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
            posted_total=Decimal(0),
            expected_emi_total=Decimal(0),
            estimated_total=Decimal(0),
            cycles=[],
        )

    latest_ids = [history[0].id for history in histories.values()]
    items_by_statement: dict[int, list[CardStatementItem]] = defaultdict(list)
    for item in db.execute(
        select(CardStatementItem).where(CardStatementItem.statement_id.in_(latest_ids))
    ).scalars():
        items_by_statement[item.statement_id].append(item)

    earliest = min(
        min(history[0].period_start or history[0].period_end, history[0].period_end + timedelta(days=1))
        for history in histories.values()
    )
    transactions = list(
        db.execute(
            select(Transaction).where(
                Transaction.txn_date >= earliest,
                Transaction.txn_date <= as_of,
                Transaction.source.in_(ExpenseRepository.LIVE_SOURCES),
            )
        ).scalars()
    )

    cycles: list[CurrentCardCycleOut] = []
    for (bank_key, last4), history in histories.items():
        latest = history[0]
        previous = history[1] if len(history) > 1 else None

        cycle_start = latest.period_end + timedelta(days=1)
        cycle_end = _next_month(latest.period_end, previous.period_end if previous else None)
        statement_date = (
            _next_month(latest.statement_date, previous.statement_date if previous else None)
            if latest.statement_date
            else cycle_end
        )

        # If statement ingestion missed one or more months, advance to the
        # cadence window containing today instead of accumulating old cycles.
        previous_end = latest.period_end
        guard = 0
        while cycle_end < as_of and guard < 24:
            previous_cycle_end = previous_end
            previous_end = cycle_end
            cycle_start = cycle_end + timedelta(days=1)
            cycle_end = _next_month(cycle_end, previous_cycle_end)
            statement_date = _next_month(statement_date)
            guard += 1

        due_date = None
        if latest.due_date and latest.statement_date:
            lag = latest.due_date - latest.statement_date
            if timedelta(days=5) <= lag <= timedelta(days=40):
                due_date = statement_date + lag

        card_transactions = [
            transaction
            for transaction in transactions
            if _account_matches(transaction.account, latest.bank, last4)
        ]
        current = [
            transaction
            for transaction in card_transactions
            if cycle_start <= transaction.txn_date <= as_of
        ]

        posted_debits = Decimal(0)
        posted_credits = Decimal(0)
        included_count = 0
        unconverted_count = 0
        last_transaction_date = None
        for transaction in current:
            if transaction.currency != "INR":
                unconverted_count += 1
                continue
            signed = _included_amount(transaction)
            if signed is None:
                continue
            included_count += 1
            if signed >= 0:
                posted_debits += signed
            else:
                posted_credits += -signed
            if last_transaction_date is None or transaction.txn_date > last_transaction_date:
                last_transaction_date = transaction.txn_date

        posted_total = posted_debits - posted_credits
        expected_emis = _explicit_expected_emis(items_by_statement[latest.id])
        expected_emi_total = sum(
            (item.estimated_amount for item in expected_emis), Decimal(0)
        )

        historical_coverage = None
        if latest.period_start and latest.total_spend and latest.total_spend > 0:
            historical_debits = sum(
                (
                    signed
                    for transaction in card_transactions
                    if latest.period_start <= transaction.txn_date <= latest.period_end
                    and (signed := _included_amount(transaction)) is not None
                    and signed > 0
                ),
                Decimal(0),
            )
            historical_coverage = min(
                Decimal(1),
                (historical_debits / latest.total_spend).quantize(
                    Decimal("0.01"), rounding=ROUND_HALF_UP
                ),
            )

        cycles.append(
            CurrentCardCycleOut(
                bank=latest.bank,
                card_label=latest.card_label,
                card_last4=last4,
                cycle_start=cycle_start,
                cycle_end=cycle_end,
                expected_statement_date=statement_date,
                expected_due_date=due_date,
                posted_debits=posted_debits,
                posted_credits=posted_credits,
                posted_total=posted_total,
                expected_emi_total=expected_emi_total,
                estimated_total=posted_total + expected_emi_total,
                expected_emis=expected_emis,
                transaction_count=included_count,
                last_transaction_date=last_transaction_date,
                historical_coverage=historical_coverage,
                confidence=_confidence(historical_coverage),
                unconverted_transaction_count=unconverted_count,
            )
        )

    cycles.sort(key=lambda cycle: (cycle.cycle_end, cycle.bank, cycle.card_last4))
    posted_total = sum((cycle.posted_total for cycle in cycles), Decimal(0))
    expected_emi_total = sum((cycle.expected_emi_total for cycle in cycles), Decimal(0))
    return CurrentCardCyclesOut(
        as_of=as_of,
        posted_total=posted_total,
        expected_emi_total=expected_emi_total,
        estimated_total=posted_total + expected_emi_total,
        cycles=cycles,
    )
