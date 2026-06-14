from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field

from app.models.health import HealthRecordType


class HealthRecordIn(BaseModel):
    source_id: str = Field(..., max_length=128, description="Stable Health Connect record id")
    type: HealthRecordType
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
    type: HealthRecordType
    value: float
    unit: str
    start_time: datetime
    end_time: datetime


class IngestResult(BaseModel):
    received: int
    inserted: int
    skipped: int
