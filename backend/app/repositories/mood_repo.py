from __future__ import annotations

from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.mood import MoodCheckin
from app.schemas.mood import MoodCheckinIn


class MoodRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def upsert(self, payload: MoodCheckinIn) -> MoodCheckin:
        existing = self.get(payload.day)
        if existing:
            existing.mood = payload.mood
            existing.energy = payload.energy
            existing.focus = payload.focus
            existing.stress = payload.stress
            existing.note = payload.note
            obj = existing
        else:
            obj = MoodCheckin(**payload.model_dump())
            self.db.add(obj)
        self.db.commit()
        self.db.refresh(obj)
        return obj

    def get(self, day: date) -> MoodCheckin | None:
        return self.db.execute(
            select(MoodCheckin).where(MoodCheckin.day == day)
        ).scalar_one_or_none()

    def range(self, start: date, end: date) -> list[MoodCheckin]:
        stmt = (
            select(MoodCheckin)
            .where(MoodCheckin.day >= start, MoodCheckin.day <= end)
            .order_by(MoodCheckin.day)
        )
        return list(self.db.execute(stmt).scalars())
