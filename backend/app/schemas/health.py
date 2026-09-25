from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class HealthRecordIn(BaseModel):
    source_id: str = Field(..., max_length=128, description="Stable source record id")
    # Free-form so any metric can be ingested, not just types LifeOS scores today.
    # Well-known values live in HealthRecordType; unknown ones are stored as-is.
    type: str = Field(..., max_length=64)
    value: float
    unit: str = Field(..., max_length=32)
    start_time: datetime
    end_time: datetime


class HealthBatchIn(BaseModel):
    """Android syncs records in batches; ingestion is idempotent by source_id."""

    records: list[HealthRecordIn]


class HealthRecordOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    source_id: str
    type: str
    value: float
    unit: str
    start_time: datetime
    end_time: datetime


class IngestResult(BaseModel):
    received: int
    inserted: int
    skipped: int
    updated: int = 0


class AutoExportData(BaseModel):
    """Inner `data` object of a Health Auto Export REST payload (lenient)."""

    model_config = ConfigDict(extra="ignore")

    metrics: list[dict] = Field(default_factory=list)
    workouts: list[dict] = Field(default_factory=list)


class AutoExportIn(BaseModel):
    """Top-level Health Auto Export REST payload: {"data": {...}}."""

    model_config = ConfigDict(extra="ignore")

    data: AutoExportData = Field(default_factory=AutoExportData)
