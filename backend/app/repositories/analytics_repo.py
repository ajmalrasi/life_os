from __future__ import annotations

from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.daily_score import DailyScore
from app.models.daily_state import DailyState, LifeState
from app.models.insight import Insight
from app.models.synthesized_insight import SynthesizedInsight
from app.models.weekly_recap import WeeklyRecap


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


class SynthesizedInsightRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def replace(self, drafts: list[dict]) -> list[SynthesizedInsight]:
        """Swap the whole set for a freshly synthesized batch.

        These insights are regenerated wholesale each nightly run, so there's no
        per-row identity to preserve — clear and reinsert. A falsy/empty batch
        leaves the existing rows untouched (best-effort: a skipped or failed
        synthesis must not wipe the last good insights the user is looking at).
        """
        if not drafts:
            return self.all()
        for row in self.db.execute(select(SynthesizedInsight)).scalars():
            self.db.delete(row)
        rows = [
            SynthesizedInsight(
                title=d["title"],
                body=d["body"],
                theme=d.get("theme"),
                action=d.get("action"),
                severity=d.get("severity"),
                cites=",".join(d.get("cites", [])),
                rank=i,
            )
            for i, d in enumerate(drafts)
        ]
        self.db.add_all(rows)
        self.db.commit()
        return self.all()

    def all(self) -> list[SynthesizedInsight]:
        stmt = select(SynthesizedInsight).order_by(SynthesizedInsight.rank)
        return list(self.db.execute(stmt).scalars())


class WeeklyRecapRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def upsert(self, week_start: date, week_end: date, prose: str) -> WeeklyRecap:
        """One row per ISO week; re-running the same week overwrites its prose
        instead of accumulating duplicates (safe to retry a failed timer run)."""
        obj = self.db.execute(
            select(WeeklyRecap).where(WeeklyRecap.week_start == week_start)
        ).scalar_one_or_none()
        if obj is None:
            obj = WeeklyRecap(week_start=week_start, week_end=week_end, prose=prose)
            self.db.add(obj)
        else:
            obj.week_end = week_end
            obj.prose = prose
        self.db.commit()
        self.db.refresh(obj)
        return obj

    def latest(self) -> WeeklyRecap | None:
        stmt = select(WeeklyRecap).order_by(WeeklyRecap.week_start.desc()).limit(1)
        return self.db.execute(stmt).scalar_one_or_none()
