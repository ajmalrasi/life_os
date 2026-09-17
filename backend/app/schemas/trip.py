from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field


class TripSyncIn(BaseModel):
    title: str = Field(..., min_length=1, max_length=300)
    base_revision: int | None = Field(default=None, ge=1)
    mutation_id: str = Field(..., min_length=1, max_length=80)
    payload: dict[str, Any]


class TripBackupOut(BaseModel):
    id: str
    title: str
    revision: int
    payload: dict[str, Any]
    updated_at: datetime


class TripSummaryOut(BaseModel):
    id: str
    title: str
    revision: int
    updated_at: datetime

