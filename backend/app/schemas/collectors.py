from __future__ import annotations

from pydantic import BaseModel


class ExecutionSyncResult(BaseModel):
    days_updated: int
    repos_scanned: int
    source: str
    today_commits: int | None = None
    today_coding_minutes: int | None = None
    message: str
