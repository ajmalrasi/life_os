from __future__ import annotations

from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.daily_score import DailyScore
from app.models.daily_state import DailyState, LifeState
from app.models.insight import Insight


class ScoreRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def upsert(self, day: date, **scores: float | None) -> DailyScore:
        obj = self.get(day)
        if obj is None:
            obj = DailyScore(day=day)
            self.db.add(obj)
        for key, value in scores.items():
            setattr(obj, key, value)
        self.db.commit()
        self.db.refresh(obj)
        return obj

    def get(self, day: date) -> DailyScore | None:
        return self.db.execute(
            select(DailyScore).where(DailyScore.day == day)
        ).scalar_one_or_none()

    def range(self, start: date, end: date) -> list[DailyScore]:
        stmt = (
            select(DailyScore)
            .where(DailyScore.day >= start, DailyScore.day <= end)
            .order_by(DailyScore.day)
        )
        return list(self.db.execute(stmt).scalars())


class StateRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def upsert(self, day: date, state: LifeState, confidence: float, rationale: str) -> DailyState:
        obj = self.db.execute(
            select(DailyState).where(DailyState.day == day)
        ).scalar_one_or_none()
        if obj is None:
            obj = DailyState(day=day)
            self.db.add(obj)
        obj.state = state
        obj.confidence = confidence
        obj.rationale = rationale
        self.db.commit()
        self.db.refresh(obj)
        return obj

    def get(self, day: date) -> DailyState | None:
        return self.db.execute(
            select(DailyState).where(DailyState.day == day)
        ).scalar_one_or_none()

    def range(self, start: date, end: date) -> list[DailyState]:
        stmt = (
            select(DailyState)
            .where(DailyState.day >= start, DailyState.day <= end)
            .order_by(DailyState.day)
        )
        return list(self.db.execute(stmt).scalars())


class InsightRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def upsert(
        self,
        driver: str,
        outcome: str,
        correlation: float,
        sample_size: int,
        lag_days: int,
        summary: str,
        narrative: str | None = None,
    ) -> Insight:
        obj = self.db.execute(
            select(Insight).where(Insight.driver == driver, Insight.outcome == outcome)
        ).scalar_one_or_none()
        if obj is None:
            obj = Insight(driver=driver, outcome=outcome)
            self.db.add(obj)
        obj.correlation = correlation
        obj.sample_size = sample_size
        obj.lag_days = lag_days
        obj.summary = summary
        # Only overwrite the narrative when we have a fresh one; preserve the
        # previous wording if narration was skipped/unavailable this run.
        if narrative is not None:
            obj.narrative = narrative
        self.db.commit()
        self.db.refresh(obj)
        return obj

    def all(self) -> list[Insight]:
        stmt = select(Insight).order_by(Insight.correlation.desc())
        return list(self.db.execute(stmt).scalars())

    def clear(self) -> None:
        for row in self.db.execute(select(Insight)).scalars():
            self.db.delete(row)
        self.db.commit()
