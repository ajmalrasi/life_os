from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.database import utcnow
from app.models.mail import (
    MailAccount,
    MailAccountPurpose,
    MailAuthSession,
    MailMessageRecord,
    MailSyncState,
)


class MailAccountRepository:
    PURPOSES = {"finance", "priority"}

    def __init__(self, db: Session) -> None:
        self.db = db

    def register(
        self, account: str, purpose: str, display_label: str | None = None, provider: str = "gmail"
    ) -> MailAccount:
        obj, assignment = self._ensure(account, purpose, display_label, provider)
        assignment.enabled = True
        self.db.commit()
        self.db.refresh(obj)
        return obj

    def _ensure(
        self, account: str, purpose: str, display_label: str | None, provider: str
    ) -> tuple[MailAccount, MailAccountPurpose]:
        normalized = account.strip().casefold()
        if not normalized or "@" not in normalized:
            raise ValueError("A valid mail account address is required")
        if purpose not in self.PURPOSES:
            raise ValueError(f"purpose must be one of: {', '.join(sorted(self.PURPOSES))}")
        obj = self.db.execute(
            select(MailAccount).where(
                MailAccount.provider == provider,
                MailAccount.provider_account_id == normalized,
            )
        ).scalar_one_or_none()
        if obj is None:
            obj = MailAccount(
                provider=provider,
                provider_account_id=normalized,
                display_label=(display_label or normalized).strip()[:80],
            )
            self.db.add(obj)
            self.db.flush()
        elif display_label:
            obj.display_label = display_label.strip()[:80]
            obj.enabled = True

        assignment = self.db.execute(
            select(MailAccountPurpose).where(
                MailAccountPurpose.mail_account_id == obj.id,
                MailAccountPurpose.purpose == purpose,
            )
        ).scalar_one_or_none()
        if assignment is None:
            assignment = MailAccountPurpose(mail_account_id=obj.id, purpose=purpose)
            self.db.add(assignment)
            self.db.flush()
        return obj, assignment

    def assign_exclusive(
        self, account: str, purpose: str, display_label: str | None = None
    ) -> MailAccount:
        """Assign one active mailbox to a LifeOS surface without deleting history."""
        obj, selected = self._ensure(account, purpose, display_label, "gmail")
        assignments = list(
            self.db.execute(
                select(MailAccountPurpose).where(MailAccountPurpose.purpose == purpose)
            ).scalars()
        )
        for assignment in assignments:
            assignment.enabled = assignment.id == selected.id
        self.db.commit()
        self.db.refresh(obj)
        return obj

    def has_purpose_assignment(self, purpose: str) -> bool:
        return self.db.execute(
            select(MailAccountPurpose.id).where(MailAccountPurpose.purpose == purpose).limit(1)
        ).scalar_one_or_none() is not None

    def for_purpose(self, purpose: str) -> list[MailAccount]:
        return list(
            self.db.execute(
                select(MailAccount)
                .join(MailAccountPurpose, MailAccountPurpose.mail_account_id == MailAccount.id)
                .where(
                    MailAccount.enabled.is_(True),
                    MailAccountPurpose.enabled.is_(True),
                    MailAccountPurpose.purpose == purpose,
                )
                .order_by(MailAccount.id)
            ).scalars()
        )

    def finance_account(self, legacy_account: str = "") -> MailAccount | None:
        accounts = self.for_purpose("finance")
        if accounts:
            return accounts[0]
        # A disabled assignment means the user intentionally disconnected it;
        # do not silently restore the legacy environment account.
        if legacy_account.strip() and not self.has_purpose_assignment("finance"):
            return self.register(legacy_account, "finance", "Finance inbox")
        return None

    def all(self) -> list[MailAccount]:
        return list(self.db.execute(select(MailAccount).order_by(MailAccount.id)).scalars())

    def purposes(self, account_id: int) -> list[str]:
        return list(
            self.db.execute(
                select(MailAccountPurpose.purpose).where(
                    MailAccountPurpose.mail_account_id == account_id,
                    MailAccountPurpose.enabled.is_(True),
                )
            ).scalars()
        )

    def disable_purpose(self, account_id: int, purpose: str) -> bool:
        assignment = self.db.execute(
            select(MailAccountPurpose).where(
                MailAccountPurpose.mail_account_id == account_id,
                MailAccountPurpose.purpose == purpose,
            )
        ).scalar_one_or_none()
        if assignment is None or not assignment.enabled:
            return False
        assignment.enabled = False
        self.db.commit()
        return True


class MailAuthSessionRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def create(
        self,
        *,
        session_id: str,
        purpose: str,
        email: str,
        display_label: str,
        redirect_uri: str,
        secret_hash: str,
        expires_at: datetime,
    ) -> MailAuthSession:
        row = MailAuthSession(
            id=session_id,
            purpose=purpose,
            expected_email=email,
            display_label=display_label,
            redirect_uri=redirect_uri,
            secret_hash=secret_hash,
            expires_at=expires_at,
        )
        self.db.add(row)
        self.db.commit()
        self.db.refresh(row)
        return row

    def get_pending(self, session_id: str) -> MailAuthSession | None:
        row = self.db.get(MailAuthSession, session_id)
        if row is None or row.state != "pending":
            return None
        expires_at = row.expires_at
        if expires_at.tzinfo is None:
            expires_at = expires_at.replace(tzinfo=timezone.utc)
        if expires_at <= datetime.now(timezone.utc):
            row.state = "expired"
            self.db.commit()
            return None
        return row

    def complete(self, row: MailAuthSession) -> None:
        row.state = "completed"
        row.last_error = None
        row.completed_at = utcnow()
        self.db.commit()

    def fail(self, row: MailAuthSession, error: str) -> None:
        row.state = "failed"
        row.last_error = error[:500]
        self.db.commit()


class MailIntakeRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def get_message(self, account_id: int, provider_message_id: str) -> MailMessageRecord | None:
        return self.db.execute(
            select(MailMessageRecord).where(
                MailMessageRecord.mail_account_id == account_id,
                MailMessageRecord.provider_message_id == provider_message_id,
            )
        ).scalar_one_or_none()

    def add_metadata(self, account_id: int, meta: dict) -> tuple[MailMessageRecord, bool]:
        message_id = str(meta.get("id") or "")
        existing = self.get_message(account_id, message_id)
        if existing:
            return existing, False
        obj = MailMessageRecord(
            mail_account_id=account_id,
            provider_message_id=message_id,
            provider_thread_id=str(meta.get("threadId") or "") or None,
            subject=str(meta.get("subject") or "")[:500],
            sender=str(meta.get("from") or "")[:500],
            received_header=str(meta.get("date") or "")[:256],
            provider_snippet=str(meta.get("snippet") or "")[:1000],
        )
        self.db.add(obj)
        self.db.flush()
        return obj, True

    def pending(self, account_id: int, limit: int = 100) -> list[MailMessageRecord]:
        return list(
            self.db.execute(
                select(MailMessageRecord)
                .where(
                    MailMessageRecord.mail_account_id == account_id,
                    MailMessageRecord.processing_state.in_(("pending", "retry")),
                )
                .order_by(MailMessageRecord.id)
                .limit(limit)
            ).scalars()
        )

    def sync_state(self, account_id: int, consumer: str) -> MailSyncState:
        row = self.db.execute(
            select(MailSyncState).where(
                MailSyncState.mail_account_id == account_id,
                MailSyncState.consumer == consumer,
            )
        ).scalar_one_or_none()
        if row is None:
            row = MailSyncState(mail_account_id=account_id, consumer=consumer)
            self.db.add(row)
            self.db.flush()
        return row

    def begin_sync(self, account_id: int, consumer: str) -> MailSyncState:
        row = self.sync_state(account_id, consumer)
        row.last_attempt_at = utcnow()
        row.last_error = None
        self.db.commit()
        return row

    def finish_sync(self, row: MailSyncState, cursor_epoch: int) -> None:
        row.cursor_epoch = max(row.cursor_epoch, cursor_epoch)
        row.last_success_at = utcnow()
        row.last_error = None
        self.db.commit()

    def fail_sync(self, row: MailSyncState, error: str) -> None:
        row.last_error = error[:500]
        self.db.commit()

    def mark_retry(self, row: MailMessageRecord, error: str) -> None:
        row.processing_attempts += 1
        row.processing_state = "retry"
        row.last_error = error[:500]
        self.db.commit()

    def mark_processed(self, row: MailMessageRecord, state: str, preview: str) -> None:
        row.processing_attempts += 1
        row.processing_state = state
        row.preview = preview[:1200]
        row.last_error = None
        row.processed_at = utcnow()
        self.db.commit()
