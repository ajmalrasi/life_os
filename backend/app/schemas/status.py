from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel


class SourceStatusOut(BaseModel):
    source: str
    last_success_at: datetime | None
    last_attempt_at: datetime | None
    last_error: str | None
    stale: bool


class SyncStatusOut(BaseModel):
    sources: list[SourceStatusOut]
