from __future__ import annotations

from datetime import date

from pydantic import BaseModel, ConfigDict, Field


class MoodCheckinIn(BaseModel):
    day: date
    mood: int = Field(..., ge=1, le=5)
    energy: int = Field(..., ge=1, le=5)
    focus: int = Field(..., ge=1, le=5)
    stress: int = Field(..., ge=1, le=5)
    note: str | None = Field(default=None, max_length=1000)


class MoodCheckinOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    day: date
    mood: int
    energy: int
    focus: int
    stress: int
    note: str | None
