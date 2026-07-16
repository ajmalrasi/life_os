from __future__ import annotations

from datetime import date, timedelta

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.api.routes.statements import build_categorizer
from app.config import Settings, get_settings
from app.database import get_db
from app.repositories.expense_repo import ExpenseRepository
from app.repositories.statement_repo import StatementRepository
from app.schemas.expense import (
    ExpenseRebuildResult,
    ExpenseSyncResult,
    RawEmailFetchResult,
    RawEmailFinalizeResult,
    RawEmailProcessResult,
    TransactionOut,
    RepaymentCandidateOut,
    RepaymentCreate,
    RepaymentOut,
)
from app.services.expense_collector import ExpenseCollector
from app.services.expense_parsers.llm_bank_parser import BANK_DOMAINS, LlmBankParser
from app.services.gmail_client import GmailClient
from app.services.raw_email_pipeline import fetch_raw_emails, process_raw_emails
from app.services.reconcile import EXCLUDED_FROM_SPEND
from app.services.repayments import RepaymentError, RepaymentService

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


def _repayment_out(reconciliation, db: Session) -> RepaymentOut:
    service = RepaymentService(db)
    debit, credit = service.transactions_for(reconciliation)
    return RepaymentOut(
        id=reconciliation.id,
        kind=reconciliation.kind,
        source=reconciliation.source,
        debit=_to_out([debit], db)[0],
        credit=_to_out([credit], db)[0],
        created_at=reconciliation.created_at,
        undone_at=reconciliation.undone_at,
    )


def _repayment_http_error(exc: RepaymentError) -> HTTPException:
    return HTTPException(status_code=exc.status_code, detail=str(exc))


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


@router.get("/{transaction_id}/repayment-candidates", response_model=list[RepaymentCandidateOut])
def repayment_candidates(
    transaction_id: int,
    window_days: int = 120,
    db: Session = Depends(get_db),
) -> list[RepaymentCandidateOut]:
    """Rank later credits that could be the return of a selected debit.

    Matching is deliberately advisory: drifting UPI handles make fuzzy identity
    useful, but only the user-confirmed POST below changes financial totals.
    """
    if not 1 <= window_days <= 365:
        raise HTTPException(status_code=422, detail="window_days must be between 1 and 365")
    try:
        candidates = RepaymentService(db).candidates(transaction_id, window_days)
    except RepaymentError as exc:
        raise _repayment_http_error(exc) from exc
    return [
        RepaymentCandidateOut(
            transaction=_to_out([candidate.transaction], db)[0],
            match_score=candidate.match_score,
            reason=candidate.reason,
        )
        for candidate in candidates
    ]


@router.post("/repayments", response_model=RepaymentOut, status_code=201)
def create_repayment(payload: RepaymentCreate, db: Session = Depends(get_db)) -> RepaymentOut:
    """Pair a debit with its returned credit and exclude both from totals."""
    try:
        reconciliation = RepaymentService(db).create(
            payload.debit_transaction_id, payload.credit_transaction_id
        )
    except RepaymentError as exc:
        db.rollback()
        raise _repayment_http_error(exc) from exc
    return _repayment_out(reconciliation, db)


@router.delete("/repayments/{repayment_id}", response_model=RepaymentOut)
def undo_repayment(repayment_id: int, db: Session = Depends(get_db)) -> RepaymentOut:
    """Undo a user-confirmed repayment while retaining its audit record."""
    try:
        reconciliation = RepaymentService(db).undo(repayment_id)
    except RepaymentError as exc:
        db.rollback()
        raise _repayment_http_error(exc) from exc
    return _repayment_out(reconciliation, db)


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
    from app.services.payee_classifier import suggest_labels_for_new_payees
    from app.services.reconcile import run_reconciliation

    run_reconciliation(db)
    # Best-effort: reconciliation must have already removed transfers/returns
    # from consideration (category is no longer None on those), so this only
    # ever sees genuine, still-uncategorized spend payees.
    suggest_labels_for_new_payees(db, report.created)
    return ExpenseSyncResult(
        parsers_run=report.parsers_run,
        emails_scanned=report.emails_scanned,
        transactions_new=report.transactions_new,
        transactions_seen=report.transactions_seen,
        source=report.source,
        message=report.message,
        cursor_epoch=report.cursor_epoch,
        transactions_deferred=report.transactions_deferred,
        new_transactions=_to_out(report.created, db),
    )


@router.post("/rebuild-llm", response_model=ExpenseRebuildResult)
def rebuild_with_llm(
    since: date, bank: str | None = None, db: Session = Depends(get_db)
) -> ExpenseRebuildResult:
    """Destructive, one-off: delete every transaction dated on/after ``since``
    and re-ingest that whole window through *only* the LLM catch-all parser
    (bypassing the regex parsers entirely), then reconcile and categorize the
    result. Does not touch the shared incremental sync cursor.

    Meant for deliberately re-deriving a historical range with the LLM
    pipeline (e.g. to pick up transactions the regex parsers' subject
    filters missed) — not part of the normal sync flow.

    Only deletes live-alert sources (the ones this pipeline can actually
    regenerate) — never touches "cc_statement" or the statement-PDF
    "bank_account" rows, which come from a separate ingestion pipeline this
    endpoint has no way to re-run.

    ``bank`` (e.g. "HDFC" or "Axis"), when given, scopes both the delete and
    the re-ingest to that one bank's emails/accounts only — banks can have
    very different statement coverage (e.g. HDFC's live-alert accounts might
    already be fully covered by statements through a recent date, while
    another bank has no statement equivalent at all), so a full rebuild often
    needs a different "since" per bank rather than one shared date.
    """
    if bank is not None and bank not in BANK_DOMAINS:
        raise HTTPException(status_code=422, detail=f"bank must be one of {list(BANK_DOMAINS)}")

    settings = get_settings()
    repo = ExpenseRepository(db)

    domains = [BANK_DOMAINS[bank]] if bank else None
    deleted = repo.delete_since(
        since, sources=StatementRepository.LIVE_SOURCES, account_prefix=bank
    )

    report = ExpenseCollector(
        db,
        _gmail_client(settings),
        parsers=[LlmBankParser(domains=domains)],
        since_date=since,
        max_per_parser=2000,
    ).sync()

    from app.services.payee_classifier import suggest_labels_for_new_payees
    from app.services.reconcile import run_reconciliation

    run_reconciliation(db)
    labels_created = suggest_labels_for_new_payees(db, report.created, max_payees=1000)

    return ExpenseRebuildResult(
        since=since,
        transactions_deleted=deleted,
        emails_scanned=report.emails_scanned,
        transactions_new=report.transactions_new,
        transactions_seen=report.transactions_seen,
        labels_created=labels_created,
        message=(
            f"deleted {deleted} existing rows since {since}, "
            f"re-ingested {report.transactions_new} via LLM, "
            f"created {labels_created} payee labels"
        ),
    )


# -- two-phase rebuild: fetch (fast, no LLM) then process (bounded batches) --------
#
# /rebuild-llm above does everything in one request — fine for a small window,
# but a multi-month backfill means a long-lived request where a single dropped
# connection loses all progress and every retry re-hits Gmail. These three
# endpoints split it apart: fetch once (idempotent), then call process
# repeatedly with a small ``limit`` until "remaining" hits 0 — each call is
# small enough to finish well inside a normal request timeout.


@router.post("/rebuild-llm/fetch", response_model=RawEmailFetchResult)
def rebuild_fetch(since: date, bank: str, db: Session = Depends(get_db)) -> RawEmailFetchResult:
    """Save every matching email's full body to raw_bank_emails — no LLM
    calls. Safe to call repeatedly; already-saved emails are skipped."""
    if bank not in BANK_DOMAINS:
        raise HTTPException(status_code=422, detail=f"bank must be one of {list(BANK_DOMAINS)}")
    settings = get_settings()
    report = fetch_raw_emails(db, _gmail_client(settings), bank=bank, since=since)
    return RawEmailFetchResult(**report.__dict__)


@router.post("/rebuild-llm/process", response_model=RawEmailProcessResult)
def rebuild_process(
    bank: str | None = None, limit: int = 15, db: Session = Depends(get_db)
) -> RawEmailProcessResult:
    """Run the LLM chain over up to ``limit`` not-yet-processed raw emails.
    Call this repeatedly (it always picks up where the last call left off)
    until ``remaining`` is 0."""
    if bank is not None and bank not in BANK_DOMAINS:
        raise HTTPException(status_code=422, detail=f"bank must be one of {list(BANK_DOMAINS)}")
    report = process_raw_emails(db, bank=bank, limit=limit)
    return RawEmailProcessResult(**report.__dict__)


@router.post("/rebuild-llm/finalize", response_model=RawEmailFinalizeResult)
def rebuild_finalize(db: Session = Depends(get_db)) -> RawEmailFinalizeResult:
    """Run reconciliation + payee categorization once, after all raw emails
    for a rebuild have been processed.

    Scoped to exactly the transactions this pipeline produced (matched via
    raw_bank_emails) — NOT the whole transaction history. Transaction.category
    is None on essentially every ordinary row regardless of age, so a naive
    "category is None" scan would treat months of already-settled payees as
    new work for the LLM categorizer.
    """
    from app.services.payee_classifier import suggest_labels_for_new_payees
    from app.services.raw_email_pipeline import transactions_from_raw_emails
    from app.services.reconcile import run_reconciliation

    run_reconciliation(db)
    new_rows = transactions_from_raw_emails(db)
    labels_created = suggest_labels_for_new_payees(db, new_rows, max_payees=1000)
    return RawEmailFinalizeResult(
        labels_created=labels_created,
        message=f"created {labels_created} payee labels",
    )
