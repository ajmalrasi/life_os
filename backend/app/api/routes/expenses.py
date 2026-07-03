from __future__ import annotations

from datetime import date, timedelta

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.routes.statements import build_categorizer
from app.config import Settings, get_settings
from app.database import get_db
from app.repositories.expense_repo import ExpenseRepository
from app.schemas.expense import ExpenseSyncResult, TransactionOut
from app.services.expense_collector import ExpenseCollector
from app.services.gmail_client import GmailClient
from app.services.reconcile import EXCLUDED_FROM_SPEND

router = APIRouter(prefix="/expenses", tags=["expenses"])


def _to_out(rows, db: Session) -> list[TransactionOut]:
    """Serialize transactions, attaching the computed spend_category so the
    client can slice by category/day/week/month offline. ``spend_category`` is
    ``None`` for rows excluded from spend (transfers, reversals, returns)."""
    categorize = build_categorizer(db)
    out: list[TransactionOut] = []
    for r in rows:
        dto = TransactionOut.model_validate(r)
        dto.spend_category = (
            None if r.category in EXCLUDED_FROM_SPEND else categorize(r.counterparty or "")
        )
        out.append(dto)
    return out


def _gmail_client(settings: Settings) -> GmailClient:
    return GmailClient(
        account=settings.gmail_account,
        keyring_password=settings.gog_keyring_password,
        gog_bin=settings.gog_bin,
        home=settings.gog_home or None,
    )


@router.get("", response_model=list[TransactionOut])
def list_transactions(
    days: int = 30,
    source: str | None = None,
    db: Session = Depends(get_db),
) -> list[TransactionOut]:
    """Transactions in the trailing window, newest first (feeds the app table)."""
    end = date.today()
    start = end - timedelta(days=days - 1)
    rows = ExpenseRepository(db).range(start, end, source=source)
    return _to_out(rows, db)


@router.get("/new", response_model=list[TransactionOut])
def new_since(after_id: int = 0, limit: int = 500, db: Session = Depends(get_db)) -> list[TransactionOut]:
    """Transactions inserted after ``after_id`` — for a client that tracks the
    last id it has seen and polls for only the new ones (oldest-first)."""
    rows = ExpenseRepository(db).since_id(after_id, limit=limit)
    return _to_out(rows, db)


@router.post("/sync", response_model=ExpenseSyncResult)
def sync_expenses(
    full: bool = False,
    db: Session = Depends(get_db),
) -> ExpenseSyncResult:
    """Read only *new* bank/UPI emails (since the stored cursor) and upsert them.

    Returns the transactions that were newly added this run. ``full=true`` forces
    a backfill over the whole window, ignoring the cursor (safe — idempotent).
    """
    settings = get_settings()
    report = ExpenseCollector(
        db,
        _gmail_client(settings),
        backfill_days=settings.expense_backfill_days,
        full=full,
    ).sync()
    from app.services.reconcile import run_reconciliation

    run_reconciliation(db)
    return ExpenseSyncResult(
        parsers_run=report.parsers_run,
        emails_scanned=report.emails_scanned,
        transactions_new=report.transactions_new,
        transactions_seen=report.transactions_seen,
        source=report.source,
        message=report.message,
        cursor_epoch=report.cursor_epoch,
        new_transactions=_to_out(report.created, db),
    )
