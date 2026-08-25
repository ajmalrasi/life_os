"""Two-phase historical rebuild: fetch raw emails first (cheap, idempotent,
resumable), then process them through the LLM chain in bounded batches.

A single big "fetch and classify everything" request has two problems in
practice: a dropped connection loses all progress (a slow LLM call has no
partial-credit), and every retry re-hits Gmail even for emails that were
already fetched fine. Splitting the two apart fixes both:

- ``fetch_raw_emails`` is pure Gmail I/O — no LLM calls at all — and skips
  any ``message_id`` already saved, so it's always safe to just run again.
- ``process_raw_emails`` runs the LLM chain over a small, bounded batch of
  not-yet-processed rows. One dropped connection only costs that batch; the
  next call picks up exactly where it left off, with zero Gmail calls.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date

from sqlalchemy.orm import Session

from app.models.transaction import Transaction
from app.repositories.expense_repo import ExpenseRepository
from app.repositories.raw_email_repo import RawEmailRepository
from app.services.expense_collector import derive_dedup_key
from app.mail.models import MailMessage
from app.services.expense_parsers.llm_bank_parser import BANK_DOMAINS, LlmBankParser
from app.mail.gmail_provider import GmailProvider
from app.services.openclaw import OpenClawClient

_FETCH_MAX_RESULTS = 2000


@dataclass
class FetchReport:
    bank: str
    emails_found: int
    emails_saved: int


def fetch_raw_emails(db: Session, client: GmailProvider, bank: str, since: date) -> FetchReport:
    """Search Gmail and save every new matching email's full body — no LLM
    calls, so this is cheap and safe to call repeatedly (already-saved
    message_ids are skipped)."""
    if not client.is_available():
        return FetchReport(bank=bank, emails_found=0, emails_saved=0)

    repo = RawEmailRepository(db)
    domain = BANK_DOMAINS[bank]
    query = f"from:{domain} after:{since:%Y/%m/%d}"

    found = saved = 0
    for meta in client.search(query, max_results=_FETCH_MAX_RESULTS):
        message_id = meta.get("id")
        if not message_id:
            continue
        found += 1
        if repo.exists(message_id):
            continue
        email = client.get_message(message_id)
        if email is None:
            continue
        repo.create(
            message_id=message_id,
            bank=bank,
            subject=email.subject,
            from_addr=email.from_addr,
            received=email.received,
            text=email.text,
            internal_date_ms=email.internal_date_ms,
        )
        saved += 1
    db.commit()
    return FetchReport(bank=bank, emails_found=found, emails_saved=saved)


@dataclass
class ProcessReport:
    processed: int
    transactions_created: int
    remaining: int
    # True if the batch stopped early because OpenClaw went unreachable
    # mid-run — the caller should treat this as "retry shortly", not "done".
    stopped_openclaw_unavailable: bool = False


def process_raw_emails(
    db: Session,
    bank: str | None = None,
    limit: int = 20,
    client: OpenClawClient | None = None,
) -> ProcessReport:
    """Run the LLM chain over up to ``limit`` not-yet-processed raw emails,
    oldest first. Commits after each email individually, so a mid-batch
    failure only loses the one email in flight, not the whole batch.

    Each email's existing transaction(s) (matched by gmail_message_id) are
    replaced, not accumulated — reprocessing the same email after a parser
    fix must produce a clean corrected row, not a duplicate.

    Checks OpenClaw's health before *every* row (not just once at the start)
    and stops the batch — without marking the current or any remaining row
    processed — the moment it's unreachable. Without this, a model that goes
    unreachable mid-batch (e.g. Ollama unloading an idle model under memory
    pressure) would have every remaining row silently marked "processed" by
    LlmBankParser's own best-effort fallback (which treats "couldn't ask" the
    same as "asked, and it's not a transaction") — permanently losing real
    transactions from a run that merely hit a transient outage.
    """
    raw_repo = RawEmailRepository(db)
    expense_repo = ExpenseRepository(db)
    client = client or OpenClawClient()
    parser = LlmBankParser(client=client)

    rows = raw_repo.unprocessed(bank=bank, limit=limit)
    created = 0
    processed = 0
    stopped_unavailable = False
    for row in rows:
        if not client.health():
            stopped_unavailable = True
            break

        db.query(Transaction).filter(Transaction.gmail_message_id == row.message_id).delete()

        email = MailMessage(
            message_id=row.message_id,
            subject=row.subject,
            from_addr=row.from_addr,
            received=row.received,
            text=row.text,
            raw_body=row.text,
            internal_date_ms=row.internal_date_ms,
        )
        for txn in parser.parse(email):
            # Same guard ExpenseCollector.sync() applies: a debit already
            # covered by a monthly statement must not also get a live-alert
            # row. Matters more here than in the normal incremental flow,
            # because this pipeline's broad since-date can span months a
            # statement has already fully covered (e.g. a bank account with
            # no statement history of its own shares a domain with cards that
            # do have one).
            if txn.direction == "debit" and expense_repo.statement_txn_exists(txn.txn_date, txn.amount):
                continue
            key = derive_dedup_key(txn, row.message_id)
            obj = expense_repo.upsert(txn, message_id=row.message_id, dedup_key=key)
            if obj is not None:
                created += 1

        raw_repo.mark_processed(row)
        db.commit()
        processed += 1

    remaining = raw_repo.count_unprocessed(bank=bank)
    return ProcessReport(
        processed=processed,
        transactions_created=created,
        remaining=remaining,
        stopped_openclaw_unavailable=stopped_unavailable,
    )


def transactions_from_raw_emails(db: Session) -> list[Transaction]:
    """Every transaction this pipeline produced — matched by gmail_message_id
    against raw_bank_emails, not by recency or id range. Deliberately NOT
    "every transaction in the table": Transaction.category is only ever set
    by reconcile.py, so it's still None on essentially every ordinary
    transaction regardless of age — scoping by that column alone would treat
    the *entire* multi-month history as "new" and run the LLM categorizer
    over hundreds of long-settled payees it has no business touching.
    """
    from app.models.raw_bank_email import RawBankEmail

    message_ids = [r[0] for r in db.query(RawBankEmail.message_id).all()]
    if not message_ids:
        return []
    return list(
        db.query(Transaction).filter(Transaction.gmail_message_id.in_(message_ids)).all()
    )
