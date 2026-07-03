from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.payee_label import PayeeLabel


class PayeeLabelRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def active_ordered(self) -> list[PayeeLabel]:
        """Active rules in evaluation order (lowest priority first, then id)."""
        stmt = (
            select(PayeeLabel)
            .where(PayeeLabel.active.is_(True))
            .order_by(PayeeLabel.priority.asc(), PayeeLabel.id.asc())
        )
        return list(self.db.execute(stmt).scalars())

    def list_all(self) -> list[PayeeLabel]:
        stmt = select(PayeeLabel).order_by(PayeeLabel.priority.asc(), PayeeLabel.id.asc())
        return list(self.db.execute(stmt).scalars())

    def get(self, label_id: int) -> PayeeLabel | None:
        return self.db.get(PayeeLabel, label_id)

    def get_by_pattern(self, pattern: str, match_type: str) -> PayeeLabel | None:
        return self.db.execute(
            select(PayeeLabel).where(
                PayeeLabel.pattern == pattern,
                PayeeLabel.match_type == match_type,
            )
        ).scalar_one_or_none()

    def create(
        self,
        *,
        pattern: str,
        category: str,
        match_type: str = "substring",
        priority: int = 100,
        active: bool = True,
        note: str | None = None,
    ) -> PayeeLabel:
        obj = PayeeLabel(
            pattern=pattern,
            category=category,
            match_type=match_type,
            priority=priority,
            active=active,
            note=note,
        )
        self.db.add(obj)
        self.db.flush()
        return obj

    def update(self, obj: PayeeLabel, **fields) -> PayeeLabel:
        for key, value in fields.items():
            if value is not None:
                setattr(obj, key, value)
        self.db.flush()
        return obj

    def delete(self, obj: PayeeLabel) -> None:
        self.db.delete(obj)
        self.db.flush()
