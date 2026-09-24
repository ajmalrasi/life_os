from __future__ import annotations

import logging
import os
import tempfile
from concurrent.futures import FIRST_COMPLETED, Future, ThreadPoolExecutor, wait
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from time import monotonic
from email.utils import parsedate_to_datetime

from sqlalchemy.orm import Session

from app.config import Settings, get_settings
from app.mail.gmail_provider import GmailProvider, MailProviderError
from app.mail.models import MailMessage
from app.models.mail import MailAccount, MailMessageRecord
from app.models.priority import PriorityItem
from app.priority.calendar import CalendarInvite, parse_ics
from app.priority.classifier import (
    PriorityClassification,
    PriorityModelUnavailable,
    ThreadContext,
    classify,
)
from app.repositories.mail_repo import MailAccountRepository, MailIntakeRepository
from app.repositories.priority_repo import PriorityRepository

logger = logging.getLogger("lifeos.priority")

# Model failures (busy, timeout, output limit) retried across hourly syncs.
_MAX_MODEL_ATTEMPTS = 5


@dataclass
class PrioritySyncReport:
    accounts_run: int = 0
    emails_found: int = 0
    emails_processed: int = 0
    items_new: int = 0
    items_updated: int = 0
    items_review: int = 0
    pending: int = 0
    message: str = ""
    deferred: int = 0
    new_items: list[PriorityItem] = field(default_factory=list)
    account_errors: list[str] = field(default_factory=list)


@dataclass
class _Classified:
    message: MailMessage
    classification: PriorityClassification


class PrioritySyncService:
    def __init__(
        self,
        db: Session,
        settings: Settings | None = None,
        budget_seconds: float | None = None,
    ) -> None:
        self.db = db
        self.settings = settings or get_settings()
        self.budget_seconds = (
            budget_seconds if budget_seconds is not None else self.settings.priority_sync_budget_seconds
        )
        self.accounts = MailAccountRepository(db)
        self.intake = MailIntakeRepository(db)
        self.priority = PriorityRepository(db)

    def _provider(self, account: MailAccount) -> GmailProvider:
        return GmailProvider(
            account=account.provider_account_id,
            keyring_password=self.settings.gog_keyring_password,
            gog_bin=self.settings.gog_bin,
            home=self.settings.gog_home or None,
        )

    @staticmethod
    def _meta_epoch(meta: dict) -> int:
        for key in ("internalDate", "internal_date_ms"):
            try:
                raw = int(meta.get(key) or 0)
                if raw:
                    return raw // 1000 if raw > 10_000_000_000 else raw
            except (TypeError, ValueError):
                pass
        try:
            parsed = parsedate_to_datetime(str(meta.get("date") or ""))
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=timezone.utc)
            return int(parsed.timestamp())
        except (TypeError, ValueError, OverflowError):
            return 0

    def _calendar_invite(self, provider: GmailProvider, message: MailMessage) -> CalendarInvite | None:
        inline = parse_ics(message.raw_body)
        if inline:
            return inline
        for attachment in message.attachments:
            filename = attachment.filename.casefold()
            mime = attachment.mime_type.casefold()
            if not (filename.endswith(".ics") or mime == "text/calendar"):
                continue
            with tempfile.TemporaryDirectory() as tmp:
                path = os.path.join(tmp, "invite.ics")
                provider.download_attachment(message.message_id, attachment.attachment_id, path)
                try:
                    with open(path, "r", encoding="utf-8", errors="replace") as handle:
                        return parse_ics(handle.read())
                except OSError:
                    return None
        return None

    @staticmethod
    def _hydrate_record(row: MailMessageRecord, message: MailMessage) -> None:
        row.provider_thread_id = message.thread_id or row.provider_thread_id
        row.subject = message.subject[:500]
        row.sender = message.from_addr[:500]
        row.recipients = ", ".join(value for value in (message.to_addrs, message.cc_addrs) if value)
        row.received_header = message.received[:256]
        row.internal_date_ms = message.internal_date_ms
        row.provider_labels = list(message.label_ids)
        row.provider_snippet = message.snippet[:1000]
        if message.internal_date_ms:
            row.received_at = datetime.fromtimestamp(message.internal_date_ms / 1000, tz=timezone.utc)

    def _thread_context(self, account: MailAccount, row: MailMessageRecord) -> ThreadContext | None:
        existing = self.priority.get_for_thread(account, row)
        if existing is None or existing.latest_mail_message_id == row.id:
            return None
        labels = existing.automatic_labels or []
        return ThreadContext(
            category=labels[0] if labels and existing.importance != "suppressed" else None,
            state=existing.automatic_state,
            summary=existing.summary,
        )

    def _classify_remote(
        self,
        provider: GmailProvider,
        provider_message_id: str,
        account_address: str,
        thread: ThreadContext | None,
    ) -> _Classified:
        """Network-only work (Gmail + model); runs on a worker thread, no DB."""
        message = provider.get_message(provider_message_id)
        if message is None:
            raise MailProviderError("Gmail returned an empty message.")
        invite = self._calendar_invite(provider, message)
        classification = classify(message, invite, account_address=account_address, thread=thread)
        return _Classified(message=message, classification=classification)

    def _apply(
        self,
        account: MailAccount,
        row: MailMessageRecord,
        future: Future,
        report: PrioritySyncReport,
    ) -> None:
        try:
            result: _Classified = future.result()
            message, classification = result.message, result.classification
            self._hydrate_record(row, message)
            self.db.commit()
            if classification.importance == "suppressed":
                existing = self.priority.get_for_thread(account, row)
                if existing is not None:
                    _, _, changed = self.priority.upsert(account, row, classification)
                    if changed:
                        report.items_updated += 1
                self.intake.mark_processed(row, "suppressed", classification.details.preview)
                report.emails_processed += 1
                return
            item, created, changed = self.priority.upsert(account, row, classification)
            self.intake.mark_processed(row, classification.importance, classification.details.preview)
            report.emails_processed += 1
            if created:
                report.items_new += 1
                report.new_items.append(item)
            elif changed:
                report.items_updated += 1
        except PriorityModelUnavailable as exc:
            self.db.rollback()
            if row.processing_attempts + 1 >= _MAX_MODEL_ATTEMPTS:
                # Stop spending model time on a message it cannot finish; it
                # stays out of the feed (never guessed) and a full sync retries it.
                self.intake.mark_processed(row, "failed", row.preview or "")
                row.last_error = str(exc)[:500]
                self.db.commit()
                logger.warning("Priority message %s gave up: %s", row.provider_message_id, exc)
            else:
                self.intake.mark_retry(row, str(exc))
                logger.warning("Priority message %s deferred: %s", row.provider_message_id, exc)
        except MailProviderError as exc:
            self.db.rollback()
            self.intake.mark_retry(row, str(exc))
            logger.warning("Priority message %s deferred: %s", row.provider_message_id, exc)
        except Exception as exc:
            self.db.rollback()
            self.intake.mark_retry(row, f"Processing error: {exc}")
            logger.exception("Priority message %s failed", row.provider_message_id)

    def sync(self, full: bool = False) -> PrioritySyncReport:
        accounts = self.accounts.for_purpose("priority")
        if not accounts:
            return PrioritySyncReport(
                message="No Priority mail account is registered.",
                account_errors=["No Priority mail account is registered."],
            )
        total = PrioritySyncReport(accounts_run=len(accounts))
        for account in accounts:
            try:
                report = self._sync_account(account, full=full)
            except MailProviderError as exc:
                total.account_errors.append(str(exc))
                continue
            total.emails_found += report.emails_found
            total.emails_processed += report.emails_processed
            total.items_new += report.items_new
            total.items_updated += report.items_updated
            total.items_review += report.items_review
            total.pending += report.pending
            total.deferred += report.deferred
            total.new_items.extend(report.new_items)
        total.message = (
            f"{total.items_new} new Priority item(s), {total.items_updated} updated, "
            f"{total.pending} pending across {len(accounts)} account(s)."
        )
        if total.pending:
            total.message += " Remaining mail is classified on the next check."
        if total.account_errors:
            total.message += " " + " ".join(total.account_errors)
        return total

    def _sync_account(self, account: MailAccount, full: bool) -> PrioritySyncReport:
        provider = self._provider(account)
        if not provider.is_available():
            raise MailProviderError(f"Gmail reader for {account.provider_account_id} is unavailable.")
        sync_state = self.intake.begin_sync(account.id, "priority")
        cursor = 0 if full else sync_state.cursor_epoch
        window = f"newer_than:{self.settings.priority_backfill_days}d" if cursor == 0 else f"after:{max(0, cursor - 1)}"
        try:
            metadata = provider.search(f"in:inbox -in:spam -in:trash {window}", max_results=250)
        except MailProviderError as exc:
            self.intake.fail_sync(sync_state, str(exc))
            raise

        report = PrioritySyncReport(accounts_run=1)
        max_cursor = cursor
        for meta in metadata:
            if not meta.get("id"):
                continue
            row, created = self.intake.add_metadata(account.id, meta)
            if created:
                report.emails_found += 1
            elif full:
                # Full sync is also the safe reclassification path after rules
                # change. Bodies are not retained, so fetch them again from the
                # provider while preserving any user-owned item overrides.
                row.processing_state = "pending"
                row.processing_attempts = 0
                row.last_error = None
            epoch = self._meta_epoch(meta)
            max_cursor = max(max_cursor, epoch)
            if row.received_at is None and epoch:
                row.received_at = datetime.fromtimestamp(epoch, tz=timezone.utc)
        if full:
            # Gmail search is capped, but already-visible older items must not
            # escape a rules cleanup simply because they fall just beyond that
            # result window.
            for row in self.priority.latest_messages_for_account(account.id):
                row.processing_state = "pending"
                row.processing_attempts = 0
                row.last_error = None
        # Durable message ids are committed before any content fetch/classifier work.
        self.db.commit()

        pending_limit = 1000 if full else 250
        cutoff = datetime.now(timezone.utc) - timedelta(days=self.settings.priority_backfill_days)
        rows = []
        for row in self.intake.pending(account.id, limit=pending_limit):
            received = row.received_at
            if received is not None and received.tzinfo is None:
                received = received.replace(tzinfo=timezone.utc)
            if received is not None and received < cutoff:
                # Outside the Priority window: no model time, never shown.
                self.intake.mark_processed(row, "skipped", row.preview or "")
                continue
            rows.append(row)
        rows = sorted(
            rows,
            key=lambda row: row.received_at.timestamp() if row.received_at else 0,
            reverse=True,  # Fresh mail first when the time budget runs out.
        )
        deadline = monotonic() + self.budget_seconds
        workers = max(1, self.settings.openclaw_max_concurrency)
        by_id = {row.id: row for row in rows}
        queue = list(rows)
        running: dict[Future, int] = {}
        with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="priority") as pool:
            while queue or running:
                while queue and len(running) < workers and monotonic() < deadline:
                    row = queue.pop(0)
                    thread = self._thread_context(account, row)
                    future = pool.submit(
                        self._classify_remote, provider, row.provider_message_id,
                        account.provider_account_id, thread,
                    )
                    running[future] = row.id
                if not running:
                    break
                done, _ = wait(running, return_when=FIRST_COMPLETED)
                for future in done:
                    row = by_id[running.pop(future)]
                    self._apply(account, row, future, report)
                    if row.processing_state == "retry" and isinstance(
                        future.exception(), PriorityModelUnavailable
                    ):
                        report.deferred += 1

        report.pending = len(self.intake.pending(account.id, limit=1000))
        self.intake.finish_sync(sync_state, max_cursor)
        return report
