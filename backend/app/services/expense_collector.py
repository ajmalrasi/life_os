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
from datetime import date, datetime, time, timezone
from zoneinfo import ZoneInfo

from sqlalchemy.orm import Session

from app.config import get_settings
from app.models.transaction import Transaction
from app.repositories.expense_repo import ExpenseRepository
from app.services.expense_parsers import ParsedTxn, Parser, ParserUnavailable, get_parsers
from app.mail.models import MailMessage
from app.services.expense_parsers.txn_time import extract_clock_time
from app.mail.gmail_provider import GmailProvider
from app.services.openclaw import OpenClawClient

logger = logging.getLogger(__name__)

_MAX_PER_PARSER = 100  # safety cap on emails fetched per parser per run

# Bank alert bodies state local clock times in IST; combine them with the
# transaction date in this zone to get the correct absolute instant.
_IST = ZoneInfo("Asia/Kolkata")


def _email_arrival(email: MailMessage) -> datetime | None:
    """The alert email's arrival instant (UTC), or None if unknown."""
    if email.internal_date_ms:
        return datetime.fromtimestamp(email.internal_date_ms / 1000, tz=timezone.utc)
    return None


def _combine_ist(txn_date: date, clock: time) -> datetime:
    """A body clock time on the transaction's date, as a UTC instant."""
    return datetime.combine(txn_date, clock, tzinfo=_IST).astimezone(timezone.utc)


@dataclass
class ExpenseSyncReport:
    parsers_run: int
    emails_scanned: int
    transactions_new: int
    transactions_seen: int
    source: str
    message: str
    cursor_epoch: int = 0
    # Emails a parser couldn't process this run because of a transient backend
    # failure (e.g. the LLM was down). They were deliberately left un-skipped —
    # the cursor is held before them so a later run retries them.
    transactions_deferred: int = 0
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
        client: GmailProvider,
        backfill_days: int = 90,
        full: bool = False,
        parsers: list[Parser] | None = None,
        since_date: date | None = None,
        max_per_parser: int = _MAX_PER_PARSER,
        openclaw: OpenClawClient | None = None,
        txn_time_enabled: bool | None = None,
    ) -> None:
        self.db = db
        self.client = client
        self.backfill_days = backfill_days
        self.full = full
        self.repo = ExpenseRepository(db)
        # Best-effort clock-time extraction. The client is built lazily on first
        # use so a run that fetches no emails never touches OpenClaw. Passing one
        # in (tests) or disabling the feature short-circuits that.
        self._openclaw = openclaw
        self._txn_time_enabled = (
            get_settings().expense_txn_time_llm_enabled
            if txn_time_enabled is None
            else txn_time_enabled
        )
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

    def _stamp_txn_time(self, email: MailMessage, parsed: list[ParsedTxn]) -> None:
        """Set ``txn_at`` on each parsed txn: the body's stated clock time when
        the LLM finds one, else the email's arrival instant. One LLM call per
        transaction-bearing email; failures fall back silently (time is
        cosmetic and must never block or defer the transaction)."""
        if not parsed:
            return
        clock: time | None = None
        if self._txn_time_enabled:
            if self._openclaw is None:
                self._openclaw = OpenClawClient()
            clock = extract_clock_time(email.text, self._openclaw)
        arrival = _email_arrival(email)
        for txn in parsed:
            txn.txn_at = _combine_ist(txn.txn_date, clock) if clock else arrival

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

        scanned = seen = deferred = 0
        created: list[Transaction] = []
        max_internal_ms = 0
        # internalDate (epoch ms) of the OLDEST email a parser couldn't process
        # this run because of a transient backend failure. The cursor must stay
        # strictly before it so the email — and everything after it — is retried
        # next run instead of being skipped forever.
        deferred_min_ms: int | None = None

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
                try:
                    parsed = list(parser.parse(email))
                except ParserUnavailable as exc:
                    # A transient dependency (e.g. the LLM backend) was down, so
                    # we can't tell whether this email is a transaction. Defer
                    # it: track the oldest such email so the cursor is held
                    # before it below. Do NOT count it as scanned/seen.
                    deferred += 1
                    deferred_min_ms = (
                        email.internal_date_ms
                        if deferred_min_ms is None
                        else min(deferred_min_ms, email.internal_date_ms)
                    )
                    logger.warning(
                        "expense sync deferred email %s via %s: %s", message_id, parser.name, exc
                    )
                    continue
                scanned += 1
                max_internal_ms = max(max_internal_ms, email.internal_date_ms)
                self._stamp_txn_time(email, parsed)
                for txn in parsed:
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

        # Advance the cursor to the newest email we saw (seconds granularity) —
        # but never past the oldest email we had to defer, so a transient LLM
        # outage can't make the cursor jump over (and permanently lose) the
        # transactions those emails carried. Re-scanning the already-succeeded
        # emails after that point next run is harmless: they dedup on Gmail
        # message id. Skipped entirely for a scoped historical rebuild
        # (``since_date``), which never touches the shared incremental cursor.
        if self.since_date is None:
            if deferred_min_ms is not None:
                # Park one second before the oldest deferral so `after:` re-includes it.
                new_cursor = (deferred_min_ms - 1000) // 1000
            elif max_internal_ms:
                new_cursor = max_internal_ms // 1000
            else:
                new_cursor = None
            # Guard against ever moving the cursor backwards.
            if new_cursor is not None and new_cursor > self.repo.get_cursor(self._cursor_key):
                self.repo.set_cursor(self._cursor_key, new_cursor)
        self.db.commit()

        new_cursor = self.repo.get_cursor(self._cursor_key)
        deferred_msg = f", {deferred} deferred (backend down)" if deferred else ""
        return ExpenseSyncReport(
            parsers_run=len(parsers),
            emails_scanned=scanned,
            transactions_new=len(created),
            transactions_seen=seen,
            source="gmail",
            message=f"{len(created)} new, {seen} existing{deferred_msg} across {len(parsers)} parser(s)",
            cursor_epoch=new_cursor,
            transactions_deferred=deferred,
            created=created,
        )
