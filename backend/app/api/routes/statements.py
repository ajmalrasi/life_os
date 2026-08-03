from __future__ import annotations

import re
from collections import defaultdict
from datetime import date, timedelta
from decimal import Decimal

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.config import Settings, get_settings
from app.database import get_db
from app.repositories.expense_repo import ExpenseRepository
from app.repositories.payee_label_repo import PayeeLabelRepository
from app.repositories.statement_repo import StatementRepository
from app.schemas.statement import (
    CardStatementOut,
    CardStatementDetailsOut,
    CardStatementItemOut,
    CategorySpend,
    CurrentCardCycleDetailsOut,
    CurrentCardCyclesOut,
    MonthTrend,
    SpendAnalysis,
    SpendTrends,
    StatementSyncResult,
    StatementPaidUpdate,
)
from app.services.gmail_client import GmailClient
from app.schemas.expense import TransactionOut
from app.services.card_cycles import current_card_cycle_details, current_card_cycles
from app.services.reconcile import EXCLUDED_FROM_SPEND
from app.services.statement_collector import StatementCollector
from app.services.statement_details import statement_breakdown

router = APIRouter(prefix="/statements", tags=["statements"])

# Deterministic keyword categorizer for the analysis rollup. Order matters —
# first match wins. Extend freely; unknowns land in "other".
_CATEGORY_RULES: list[tuple[str, re.Pattern]] = [
    # "petro" catches petrol, petroleum, and "PETRO PARK" style pump names.
    # EMI must precede merchant categories: Axis descriptions can end in
    # "DEPT STORES", which otherwise mislabels the installment as shopping.
    ("emi", re.compile(r"\bemi\b|\binstal+ment\b|\binstallment\b|\bflexipay\b", re.I)),
    ("fuel", re.compile(r"petro|fuel|hpcl|bpcl", re.I)),
    ("wallet_load", re.compile(r"mobikwik|paytm wallet|wallet load", re.I)),
    ("food", re.compile(r"zomato|swiggy|kfc|restaurant|cafe|hotel|bakery|kitchen|chicking|food", re.I)),
    ("groceries", re.compile(r"grocery|hypermarket|supermarket|mart|fresh|traders", re.I)),
    ("shopping", re.compile(r"flipkart|myntra|amazon|decathlon|snitch|store", re.I)),
    ("subscriptions", re.compile(r"anthropic|cursor|apple|youtube|google|netflix|zee|spotify|adobe|prime|jio|\bairtel\b", re.I)),
    ("investments", re.compile(r"zerodha|broking|groww|smallcase|mutual\s*fund", re.I)),
    ("entertainment", re.compile(r"bookmyshow|cinema|pvr|inox|movie", re.I)),
    ("emi_fees", re.compile(r"gst|igst|fee|interest|surcharge|markup", re.I)),
    ("travel", re.compile(r"bus|irctc|uber|ola|rapido|airline|flight|agoda|makemytrip|goibibo|oyo|redbus", re.I)),
]


def _categorize(desc: str) -> str:
    for name, pat in _CATEGORY_RULES:
        if pat.search(desc):
            return name
    return "other"


def build_categorizer(db: Session):
    """Return a ``categorize(desc) -> category`` callable that consults the
    user's payee labels first, then falls back to the built-in keyword rules.

    Labels are loaded once here (not per-transaction) so the hot loops in the
    analysis endpoints stay a single DB round-trip. User labels take precedence
    over the keyword defaults so an explicit rule always wins; ties fall through
    in (priority, id) order as returned by the repository.
    """
    labels = PayeeLabelRepository(db).active_ordered()
    compiled: list[tuple[str, object, str]] = []
    for lbl in labels:
        if lbl.match_type == "regex":
            try:
                compiled.append(("regex", re.compile(lbl.pattern, re.I), lbl.category))
            except re.error:
                # A malformed pattern shouldn't break categorization for the
                # whole feed; skip it (create-time validation guards against
                # this, but be defensive against rows written another way).
                continue
        else:
            compiled.append((lbl.match_type, lbl.pattern.lower(), lbl.category))

    def categorize(desc: str) -> str:
        text = desc or ""
        lowered = text.lower()
        for match_type, needle, category in compiled:
            if match_type == "regex":
                if needle.search(text):  # type: ignore[union-attr]
                    return category
            elif match_type == "exact":
                if lowered == needle:
                    return category
            else:  # substring
                if needle in lowered:  # type: ignore[operator]
                    return category
        return _categorize(text)

    return categorize


def _gmail_client(settings: Settings) -> GmailClient:
    return GmailClient(
        account=settings.gmail_account,
        keyring_password=settings.gog_keyring_password,
        gog_bin=settings.gog_bin,
        home=settings.gog_home or None,
    )


@router.get("", response_model=list[CardStatementOut])
def list_statements(months: int = 3, db: Session = Depends(get_db)) -> list[CardStatementOut]:
    """Stored statement summaries, newest first."""
    end = date.today()
    start = end - timedelta(days=months * 31)
    rows = StatementRepository(db).range(start, end)
    return [CardStatementOut.model_validate(r) for r in rows]


@router.patch("/{statement_id}/paid", response_model=CardStatementOut)
def update_statement_paid(
    statement_id: int,
    payload: StatementPaidUpdate,
    db: Session = Depends(get_db),
) -> CardStatementOut:
    """Mark a card statement paid early, or undo that confirmation."""
    statement = StatementRepository(db).set_paid(statement_id, payload.paid)
    if statement is None:
        raise HTTPException(status_code=404, detail="Statement not found")
    return CardStatementOut.model_validate(statement)


@router.get("/{statement_id}/details", response_model=CardStatementDetailsOut)
def statement_details(
    statement_id: int,
    db: Session = Depends(get_db),
) -> CardStatementDetailsOut:
    """One stored bill plus explanatory rows that never enter spend totals."""
    statement, items = StatementRepository(db).details(statement_id)
    if statement is None:
        raise HTTPException(status_code=404, detail="Statement not found")
    return CardStatementDetailsOut(
        statement=CardStatementOut.model_validate(statement),
        items=[CardStatementItemOut.model_validate(item) for item in items],
        breakdown=statement_breakdown(items, statement.total_due),
    )


@router.post("/sync", response_model=StatementSyncResult)
def sync_statements(window_days: int = 40, db: Session = Depends(get_db)) -> StatementSyncResult:
    """Fetch statement PDFs and store bill summaries plus EMI costs (idempotent).

    ``window_days`` widens the Gmail search window for one-off backfills (e.g.
    pulling 6 months of history); the default matches routine monthly polling.
    Ordinary line items never enter ``transactions``; live alerts cover normal
    purchases while statement-only EMI installments/fees fill that known gap.
    """
    settings = get_settings()
    report = StatementCollector(
        db,
        _gmail_client(settings),
        owner_name=settings.cc_owner_name,
        owner_dob=settings.cc_owner_dob,
        password_overrides=settings.cc_pdf_password_overrides,
        window_days=window_days,
    ).sync()
    return StatementSyncResult(
        banks_run=report.banks_run,
        emails_seen=report.emails_seen,
        statements_new=report.statements_new,
        line_items_ingested=report.line_items_ingested,
        line_items_stored=report.line_items_stored,
        line_items_deduped=report.line_items_deduped,
        message=report.message,
        statements=[CardStatementOut.model_validate(s) for s in report.statements],
    )


@router.get("/cycles/current", response_model=CurrentCardCyclesOut)
def get_current_card_cycles(db: Session = Depends(get_db)) -> CurrentCardCyclesOut:
    """Exact recorded spend inside each card's active billing cycle."""
    return current_card_cycles(db)


@router.get(
    "/cycles/current/{bank}/{card_last4}",
    response_model=CurrentCardCycleDetailsOut,
)
def get_current_card_cycle_details(
    bank: str,
    card_last4: str,
    db: Session = Depends(get_db),
) -> CurrentCardCycleDetailsOut:
    """Recorded transactions and exact dates for one active card cycle."""
    result = current_card_cycle_details(db, bank, card_last4)
    if result is None:
        raise HTTPException(status_code=404, detail="Current card cycle not found")
    cycle, transactions = result
    categorize = build_categorizer(db)
    rows: list[TransactionOut] = []
    for transaction in transactions:
        output = TransactionOut.model_validate(transaction)
        output.spend_category = (
            transaction.spend_category_override
            or categorize(transaction.counterparty or "")
        )
        rows.append(output)
    return CurrentCardCycleDetailsOut(cycle=cycle, transactions=rows)


@router.get("/trends", response_model=SpendTrends)
def spend_trends(months: int = 6, db: Session = Depends(get_db)) -> SpendTrends:
    """Month-by-month spend from stored transactions, then an aggregate across
    the window. New individual transactions come only from live alerts."""
    end = date.today()
    start = (end.replace(day=1) - timedelta(days=(months - 1) * 31)).replace(day=1)
    txns = ExpenseRepository(db).range(start, end)
    categorize = build_categorizer(db)

    per_month: dict[str, list] = defaultdict(list)
    for t in txns:
        if t.category in EXCLUDED_FROM_SPEND:
            continue  # CC bill payments / lent-and-returned money are not spend
        per_month[t.txn_date.strftime("%Y-%m")].append(t)

    out: list[MonthTrend] = []
    agg_cat: dict[str, list[Decimal]] = defaultdict(list)
    agg_debits = Decimal(0)
    for month in sorted(per_month):
        rows = per_month[month]
        debits = [t for t in rows if t.direction == "debit"]
        credits = [t for t in rows if t.direction == "credit"]
        by_cat: dict[str, list[Decimal]] = defaultdict(list)
        by_acct: dict[str, list[Decimal]] = defaultdict(list)
        for t in debits:
            cat = t.spend_category_override or categorize(t.counterparty or "")
            by_cat[cat].append(t.amount)
            agg_cat[cat].append(t.amount)
            by_acct[t.account or "unknown"].append(t.amount)
        month_debits = sum((t.amount for t in debits), Decimal(0))
        agg_debits += month_debits
        out.append(
            MonthTrend(
                month=month,
                total_debits=month_debits,
                total_credits=sum((t.amount for t in credits), Decimal(0)),
                txn_count=len(rows),
                by_category=sorted(
                    (CategorySpend(category=k, total=sum(v), count=len(v)) for k, v in by_cat.items()),
                    key=lambda c: c.total,
                    reverse=True,
                ),
                by_account=sorted(
                    (CategorySpend(category=k, total=sum(v), count=len(v)) for k, v in by_acct.items()),
                    key=lambda c: c.total,
                    reverse=True,
                ),
            )
        )

    return SpendTrends(
        months=out,
        aggregate_debits=agg_debits,
        aggregate_by_category=sorted(
            (CategorySpend(category=k, total=sum(v), count=len(v)) for k, v in agg_cat.items()),
            key=lambda c: c.total,
            reverse=True,
        ),
    )


@router.get("/analysis", response_model=SpendAnalysis)
def spend_analysis(days: int = 45, db: Session = Depends(get_db)) -> SpendAnalysis:
    """Month-at-a-glance: dues across every card (latest statement per card)
    plus category/merchant breakdown over the combined transaction feed."""
    today = date.today()
    # Drop cards whose due date has already passed — a lapsed bill is not an
    # upcoming due and must not keep accumulating into the totals.
    cards = [
        c
        for c in StatementRepository(db).latest_per_card()
        if c.paid_at is None and not (c.due_date and c.due_date < today)
    ]
    total_due = sum((c.total_due or Decimal(0)) for c in cards)
    min_due = sum((c.min_due or Decimal(0)) for c in cards)
    upcoming = [c.due_date for c in cards if c.due_date and c.due_date >= today]
    next_due = min(upcoming) if upcoming else None

    end = today
    start = end - timedelta(days=days - 1)
    txns = ExpenseRepository(db).range(start, end)
    categorize = build_categorizer(db)

    by_cat: dict[str, list[Decimal]] = defaultdict(list)
    by_merchant: dict[str, list[Decimal]] = defaultdict(list)
    for t in txns:
        if t.direction != "debit" or t.category in EXCLUDED_FROM_SPEND:
            continue
        desc = t.counterparty or ""
        by_cat[t.spend_category_override or categorize(desc)].append(t.amount)
        if desc:
            by_merchant[desc[:48]].append(t.amount)

    categories = sorted(
        (CategorySpend(category=k, total=sum(v), count=len(v)) for k, v in by_cat.items()),
        key=lambda c: c.total,
        reverse=True,
    )
    merchants = sorted(
        (CategorySpend(category=k, total=sum(v), count=len(v)) for k, v in by_merchant.items()),
        key=lambda c: c.total,
        reverse=True,
    )[:15]

    return SpendAnalysis(
        total_due_all_cards=total_due,
        min_due_all_cards=min_due,
        next_due_date=next_due,
        cards=[CardStatementOut.model_validate(c) for c in cards],
        spend_by_category=categories,
        top_merchants=merchants,
    )
