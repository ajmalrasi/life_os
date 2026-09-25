from __future__ import annotations

from datetime import date, datetime, timezone
from typing import Literal

from pydantic import BaseModel, ConfigDict, field_validator

from app.models.daily_state import LifeState


class DailyScoreOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    day: date
    sleep_score: float | None
    recovery_score: float | None
    execution_score: float | None
    wellness_score: float | None
    fitness_score: float | None
    consistency_score: float | None
    burnout_risk: float | None


class DailyStateOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    day: date
    state: LifeState
    confidence: float
    rationale: str | None


class TodayBriefOut(BaseModel):
    state: LifeState
    confidence: str
    headline: str
    summary: str
    suggestion: str
    generated_at: datetime

    @field_validator("generated_at")
    @classmethod
    def _to_utc(cls, value: datetime) -> datetime:
        # Session tz is Asia/Kolkata; Android's Instant.parse() needs 'Z'.
        return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)


class TodayOut(BaseModel):
    day: date
    brief: TodayBriefOut | None
    # ready: brief matches the current data. refreshing: data changed and a new
    # brief is being written (the previous one, if any, is still returned).
    # unavailable: the model is switched off.
    brief_status: Literal["ready", "refreshing", "unavailable"]
    health_last_received_at: datetime | None

    @field_validator("health_last_received_at")
    @classmethod
    def _to_utc(cls, value: datetime | None) -> datetime | None:
        if value is None:
            return None
        return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)


class InsightOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    driver: str
    outcome: str
    correlation: float
    sample_size: int
    lag_days: int
    summary: str
    narrative: str | None = None


class SynthesizedInsightOut(BaseModel):
    """An LLM-synthesized, cross-domain insight grounded in cited evidence."""

    title: str
    body: str
    theme: str | None = None
    action: str | None = None
    severity: str | None = None
    cites: list[str] = []


class WeeklyRecapOut(BaseModel):
    week_start: date
    week_end: date
    prose: str


class RecomputeResult(BaseModel):
    days_scored: int
    states_classified: int
    insights_found: int
    execution_days_updated: int = 0
    execution_repos_scanned: int = 0
    execution_today_commits: int | None = None
    execution_today_coding_minutes: int | None = None
    execution_message: str | None = None
