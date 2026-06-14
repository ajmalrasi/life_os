from __future__ import annotations

from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.github import GithubActivity
from app.schemas.github import GithubActivityIn


class GithubRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def upsert(self, payload: GithubActivityIn) -> GithubActivity:
        existing = self.get(payload.day)
        if existing:
            for field, value in payload.model_dump().items():
                setattr(existing, field, value)
            obj = existing
        else:
            obj = GithubActivity(**payload.model_dump())
            self.db.add(obj)
        self.db.commit()
        self.db.refresh(obj)
        return obj

    def get(self, day: date) -> GithubActivity | None:
        return self.db.execute(
            select(GithubActivity).where(GithubActivity.day == day)
        ).scalar_one_or_none()

    def range(self, start: date, end: date) -> list[GithubActivity]:
        stmt = (
            select(GithubActivity)
            .where(GithubActivity.day >= start, GithubActivity.day <= end)
            .order_by(GithubActivity.day)
        )
        return list(self.db.execute(stmt).scalars())
