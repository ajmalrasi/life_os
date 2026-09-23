from __future__ import annotations

from datetime import datetime, timezone

from pydantic import BaseModel, field_validator


class SourceStatusOut(BaseModel):
    source: str
    last_success_at: datetime | None
    last_attempt_at: datetime | None
    last_error: str | None
    stale: bool

    # The DB session runs in Asia/Kolkata (see AGENTS.md §5), so a raw column
    # read (CollectorState.updated_at, HealthRecord.created_at, ...) comes
    # back with a +05:30 offset rather than the UTC 'Z' every other endpoint
    # in this API emits. Normalize here so Android's Instant.parse() (which
    # only accepts 'Z') doesn't need a source-specific exception.
    @field_validator("last_success_at", "last_attempt_at")
    @classmethod
    def _to_utc(cls, value: datetime | None) -> datetime | None:
        if value is None:
            return None
        return value.astimezone(timezone.utc)


class SyncStatusOut(BaseModel):
    sources: list[SourceStatusOut]
