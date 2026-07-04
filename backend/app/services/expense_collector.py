"""Collect financial transactions from bank / UPI / statement emails.

Incremental by design: a per-mailbox cursor (the Gmail ``internalDate`` of the
newest email already processed) is persisted in ``collector_state``. Each run
fetches only emails *after* that cursor — not the whole history — then advances
it. The first run (cursor unset) seeds a backfill window so the store starts
with recent history. Rows are also idempotent on a derived ``dedup_key``, so an
overlapping boundary is never double counted.
"""

from __future__ import annotations

import hashlib
import logging
from dataclasses import dataclass, field
from datetime import date

from sqlalchemy.orm import Session

from app.models.transaction import Transaction
from app.repositories.expense_repo import ExpenseRepository
from app.services.expense_parsers import ParsedTxn, Parser, get_parsers
from app.services.gmail_client import GmailClient

logger = logging.getLogger(__name__)

_MAX_PER_PARSER = 100  # safety cap on emails fetched per parser per run


@dataclass
class ExpenseSyncReport:
    parsers_run: int
    emails_scanned: int
    transactions_new: int
    transactions_seen: int
    source: str
    message: str
    cursor_epoch: int = 0
    created: list[Transaction] = field(default_factory=list)


def derive_dedup_key(txn: ParsedTxn, message_id: str) -> str:
    if txn.dedup_key:
        return txn.dedup_key
    if txn.reference:
        return f"{txn.source}:ref:{txn.reference}"
    basis = "|".join(
        str(x)
        for x in (txn.source, txn.txn_date, txn.amount, txn.direction, txn.counterparty, message_id)
    )
    return f"{txn.source}:h:" + hashlib.sha256(basis.encode()).hexdigest()[:24]


class ExpenseCollector:
    def __init__(
        self,
        db: Session,
        client: GmailClient,
        backfill_days: int = 90,
        full: bool = False,
        parsers: list[Parser] | None = None,
        since_date: date | None = None,
        max_per_parser: int = _MAX_PER_PARSER,
    ) -> None:
        self.db = db
        self.client = client
        self.backfill_days = backfill_days
        self.full = full
        self.repo = ExpenseRepository(db)
        # Overriding ``parsers`` scopes a run to specific sources (e.g. a
        # one-off historical rebuild through only the LLM catch-all parser,
        # bypassing the regex ones). ``since_date`` overrides the normal
        # cursor/backfill-window logic with an explicit start date and skips
        # advancing the shared cursor, so a scoped rebuild of old history
        # never affects the next normal incremental sync.
        self.parsers = parsers
        self.since_date = since_date
        self.max_per_parser = max_per_parser

    @property
    def _cursor_key(self) -> str:
        return f"gmail_expenses:{self.client.account}"

    def sync(self) -> ExpenseSyncReport:
        parsers = self.parsers if self.parsers is not None else get_parsers()
        if not self.client.is_available():
            return ExpenseSyncReport(
                parsers_run=0,
                emails_scanned=0,
                transactions_new=0,
                transactions_seen=0,
                source="gmail",
                message="gog CLI unavailable or gmail_account unset",
            )

        if self.since_date is not None:
            window = f"after:{self.since_date:%Y/%m/%d}"
        else:
            cursor = 0 if self.full else self.repo.get_cursor(self._cursor_key)
            # First run (or forced full) seeds recent history; afterwards, pull only new.
            window = f"newer_than:{self.backfill_days}d" if cursor == 0 else f"after:{cursor}"

        scanned = seen = 0
        created: list[Transaction] = []
        max_internal_ms = 0

        for parser in parsers:
            query = f"{parser.gmail_query} {window}"
            for meta in self.client.search(query, max_results=self.max_per_parser):
                message_id = meta.get("id")
                if not message_id:
                    continue
                if self.repo.has_message(message_id):
                    seen += 1
                    continue
                email = self.client.get_message(message_id)
                if email is None:
                    continue
                scanned += 1
                max_internal_ms = max(max_internal_ms, email.internal_date_ms)
                for txn in parser.parse(email):
                    # Guards a backfill/late-add scenario: a live parser added
                    # (or backfilled) after its statement period was already
                    # ingested would otherwise double-count each transaction.
                    if txn.direction == "debit" and self.repo.statement_txn_exists(txn.txn_date, txn.amount):
                        seen += 1
                        continue
                    key = derive_dedup_key(txn, message_id)
                    obj = self.repo.upsert(txn, message_id=message_id, dedup_key=key)
                    if obj is not None:
                        created.append(obj)
                    else:
                        seen += 1

        # Advance the cursor to the newest email we saw (seconds granularity).
        # Skipped for a scoped historical rebuild — its emails are all old,
        # so this would be a no-op at best, but never risk it regressing or
        # confusing the next normal incremental sync's window.
        if max_internal_ms and self.since_date is None:
            self.repo.set_cursor(self._cursor_key, max_internal_ms // 1000)
        self.db.commit()

        new_cursor = self.repo.get_cursor(self._cursor_key)
        return ExpenseSyncReport(
            parsers_run=len(parsers),
            emails_scanned=scanned,
            transactions_new=len(created),
            transactions_seen=seen,
            source="gmail",
            message=f"{len(created)} new, {seen} existing across {len(parsers)} parser(s)",
            cursor_epoch=new_cursor,
            created=created,
        )
