from __future__ import annotations

from datetime import date

from pydantic import BaseModel, ConfigDict

from app.models.daily_state import LifeState


class DailyScoreOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    day: date
    sleep_score: float | None
    recovery_score: float | None
    execution_score: float | None
    consistency_score: float | None
    burnout_risk: float | None


class DailyStateOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    day: date
    state: LifeState
    confidence: float
    rationale: str | None


class InsightOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    driver: str
    outcome: str
    correlation: float
    sample_size: int
    lag_days: int
    summary: str


class RecomputeResult(BaseModel):
    days_scored: int
    states_classified: int
    insights_found: int
