from __future__ import annotations

from datetime import date, timedelta

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.database import get_db
from app.repositories.analytics_repo import (
    InsightRepository,
    ScoreRepository,
    StateRepository,
)
from app.schemas.analytics import (
    DailyScoreOut,
    DailyStateOut,
    InsightOut,
    RecomputeResult,
)
from app.services.analytics import AnalyticsService

router = APIRouter(prefix="/analytics", tags=["analytics"])


@router.post("/recompute", response_model=RecomputeResult)
def recompute(days: int = 60, db: Session = Depends(get_db)) -> RecomputeResult:
    """Recompute scores + states for the trailing window and rediscover patterns.

    This is the 'nightly analysis' entrypoint; it can also be triggered on demand
    (e.g. right after a sync) so the dashboard is always current.
    """
    end = date.today()
    start = end - timedelta(days=days - 1)
    scored, states, insights = AnalyticsService(db).run_nightly(start, end)
    return RecomputeResult(days_scored=scored, states_classified=states, insights_found=insights)


@router.get("/scores", response_model=list[DailyScoreOut])
def get_scores(days: int = 30, db: Session = Depends(get_db)) -> list[DailyScoreOut]:
    end = date.today()
    start = end - timedelta(days=days - 1)
    rows = ScoreRepository(db).range(start, end)
    return [DailyScoreOut.model_validate(r) for r in rows]


@router.get("/scores/{day}", response_model=DailyScoreOut)
def get_score(day: date, db: Session = Depends(get_db)) -> DailyScoreOut:
    row = ScoreRepository(db).get(day)
    if row is None:
        raise HTTPException(status_code=404, detail="No scores for that day (run /analytics/recompute)")
    return DailyScoreOut.model_validate(row)


@router.get("/state", response_model=list[DailyStateOut])
def get_states(days: int = 30, db: Session = Depends(get_db)) -> list[DailyStateOut]:
    end = date.today()
    start = end - timedelta(days=days - 1)
    rows = StateRepository(db).range(start, end)
    return [DailyStateOut.model_validate(r) for r in rows]


@router.get("/insights", response_model=list[InsightOut])
def get_insights(db: Session = Depends(get_db)) -> list[InsightOut]:
    rows = InsightRepository(db).all()
    return [InsightOut.model_validate(r) for r in rows]
