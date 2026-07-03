from __future__ import annotations

import re
from datetime import datetime

from pydantic import BaseModel, ConfigDict, field_validator, model_validator

from app.models.payee_label import MATCH_TYPES


class PayeeLabelIn(BaseModel):
    """Payload for creating a payee label."""

    pattern: str
    category: str
    match_type: str = "substring"
    priority: int = 100
    active: bool = True
    note: str | None = None

    @field_validator("pattern", "category")
    @classmethod
    def _non_empty(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("must not be empty")
        return v

    @field_validator("match_type")
    @classmethod
    def _known_match_type(cls, v: str) -> str:
        if v not in MATCH_TYPES:
            raise ValueError(f"match_type must be one of {MATCH_TYPES}")
        return v

    @model_validator(mode="after")
    def _regex_compiles(self) -> "PayeeLabelIn":
        if self.match_type == "regex":
            try:
                re.compile(self.pattern)
            except re.error as exc:
                raise ValueError(f"invalid regex pattern: {exc}") from exc
        return self


class PayeeLabelUpdate(BaseModel):
    """Partial update — only provided fields are changed."""

    pattern: str | None = None
    category: str | None = None
    match_type: str | None = None
    priority: int | None = None
    active: bool | None = None
    note: str | None = None

    @field_validator("match_type")
    @classmethod
    def _known_match_type(cls, v: str | None) -> str | None:
        if v is not None and v not in MATCH_TYPES:
            raise ValueError(f"match_type must be one of {MATCH_TYPES}")
        return v


class PayeeLabelOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    pattern: str
    category: str
    match_type: str
    priority: int
    active: bool
    note: str | None
    created_at: datetime
    updated_at: datetime
