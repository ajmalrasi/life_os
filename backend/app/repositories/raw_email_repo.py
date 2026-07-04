from __future__ import annotations

from datetime import datetime

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.database import utcnow
from app.models.raw_bank_email import RawBankEmail


class RawEmailRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def exists(self, message_id: str) -> bool:
        count = self.db.execute(
            select(func.count()).select_from(RawBankEmail).where(RawBankEmail.message_id == message_id)
        ).scalar_one()
        return count > 0

    def create(
        self,
        *,
        message_id: str,
        bank: str,
        subject: str,
        from_addr: str,
        received: str,
        text: str,
        internal_date_ms: int,
    ) -> RawBankEmail:
        obj = RawBankEmail(
            message_id=message_id,
            bank=bank,
            subject=subject,
            from_addr=from_addr,
            received=received,
            text=text,
            internal_date_ms=internal_date_ms,
        )
        self.db.add(obj)
        self.db.flush()
        return obj

    def unprocessed(self, bank: str | None = None, limit: int = 20) -> list[RawBankEmail]:
        stmt = select(RawBankEmail).where(RawBankEmail.processed_at.is_(None))
        if bank:
            stmt = stmt.where(RawBankEmail.bank == bank)
        stmt = stmt.order_by(RawBankEmail.internal_date_ms.asc()).limit(limit)
        return list(self.db.execute(stmt).scalars())

    def count_unprocessed(self, bank: str | None = None) -> int:
        stmt = select(func.count()).select_from(RawBankEmail).where(RawBankEmail.processed_at.is_(None))
        if bank:
            stmt = stmt.where(RawBankEmail.bank == bank)
        return self.db.execute(stmt).scalar_one()

    def mark_processed(self, obj: RawBankEmail, when: datetime | None = None) -> None:
        obj.processed_at = when or utcnow()
        self.db.flush()
