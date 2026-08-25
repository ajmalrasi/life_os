from __future__ import annotations

import logging
import os
import tempfile
from dataclasses import dataclass, field
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime

from sqlalchemy.orm import Session

from app.config import Settings, get_settings
from app.mail.gmail_provider import GmailProvider, MailProviderError
from app.mail.models import MailMessage
from app.models.mail import MailAccount, MailMessageRecord
from app.models.priority import PriorityItem
from app.priority.ambiguity import review_ambiguous
from app.priority.calendar import CalendarInvite, parse_ics
from app.priority.classifier import classify
from app.repositories.mail_repo import MailAccountRepository, MailIntakeRepository
from app.repositories.priority_repo import PriorityRepository

logger = logging.getLogger("lifeos.priority")


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
    new_items: list[PriorityItem] = field(default_factory=list)
    account_errors: list[str] = field(default_factory=list)


class PrioritySyncService:
    def __init__(self, db: Session, settings: Settings | None = None) -> None:
        self.db = db
        self.settings = settings or get_settings()
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
            total.new_items.extend(report.new_items)
        total.message = (
            f"{total.items_new} new Priority item(s), {total.items_updated} updated, "
            f"{total.pending} pending across {len(accounts)} account(s)."
        )
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
            epoch = self._meta_epoch(meta)
            max_cursor = max(max_cursor, epoch)
            if row.received_at is None and epoch:
                row.received_at = datetime.fromtimestamp(epoch, tz=timezone.utc)
        # Durable message ids are committed before any content fetch/classifier work.
        self.db.commit()

        for row in self.intake.pending(account.id, limit=250):
            try:
                message = provider.get_message(row.provider_message_id)
                if message is None:
                    raise MailProviderError("Gmail returned an empty message.")
                self._hydrate_record(row, message)
                self.db.commit()
                max_cursor = max(max_cursor, message.internal_date_ms // 1000)
                invite = self._calendar_invite(provider, message)
                classification = classify(message, invite)
                if classification.importance == "review" and self.settings.priority_llm_enabled:
                    classification = review_ambiguous(message, classification)
                if classification.importance == "suppressed":
                    self.intake.mark_processed(row, "suppressed", classification.details.preview)
                    report.emails_processed += 1
                    continue
                item, created, changed = self.priority.upsert(account, row, classification)
                self.intake.mark_processed(row, classification.importance, classification.details.preview)
                report.emails_processed += 1
                if created:
                    report.items_new += 1
                    report.new_items.append(item)
                elif changed:
                    report.items_updated += 1
                if classification.importance == "review":
                    report.items_review += 1
            except MailProviderError as exc:
                self.intake.mark_retry(row, str(exc))
                logger.warning("Priority message %s deferred: %s", row.provider_message_id, exc)
            except Exception as exc:
                self.intake.mark_retry(row, f"Processing error: {exc}")
                logger.exception("Priority message %s failed", row.provider_message_id)

        report.pending = len(self.intake.pending(account.id, limit=1000))
        self.intake.finish_sync(sync_state, max_cursor)
        return report
