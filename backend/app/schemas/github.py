from __future__ import annotations

from datetime import date

from pydantic import BaseModel, ConfigDict, Field


class GithubActivityIn(BaseModel):
    day: date
    commits: int = Field(default=0, ge=0)
    additions: int = Field(default=0, ge=0)
    deletions: int = Field(default=0, ge=0)
    active_repos: int = Field(default=0, ge=0)
    coding_minutes: int = Field(default=0, ge=0)
    prs_opened: int = Field(default=0, ge=0)
    prs_merged: int = Field(default=0, ge=0)
    issues_opened: int = Field(default=0, ge=0)
    issues_closed: int = Field(default=0, ge=0)


class GithubActivityOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    day: date
    commits: int
    additions: int
    deletions: int
    active_repos: int
    coding_minutes: int
    prs_opened: int
    prs_merged: int
    issues_opened: int
    issues_closed: int
