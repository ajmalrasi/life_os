"""Records/reads per-source sync attempts (see app.models.sync_status)."""

from __future__ import annotations

from sqlalchemy.orm import Session

from app.database import utcnow
from app.models.sync_status import SyncAttempt


class SyncAttemptRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def _get_or_create(self, source: str) -> SyncAttempt:
        row = self.db.get(SyncAttempt, source)
        if row is None:
            row = SyncAttempt(source=source)
            self.db.add(row)
        return row

    def record_attempt(self, source: str) -> None:
        row = self._get_or_create(source)
        row.last_attempt_at = utcnow()
        self.db.commit()

    def record_success(self, source: str) -> None:
        row = self._get_or_create(source)
        now = utcnow()
        row.last_attempt_at = now
        row.last_success_at = now
        row.last_error = None
        self.db.commit()

    def record_error(self, source: str, error: str) -> None:
        row = self._get_or_create(source)
        row.last_attempt_at = utcnow()
        row.last_error = error[:500]
        self.db.commit()

    def get(self, source: str) -> SyncAttempt | None:
        return self.db.get(SyncAttempt, source)

    def all(self) -> list[SyncAttempt]:
        return list(self.db.query(SyncAttempt).all())
