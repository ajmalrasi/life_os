from __future__ import annotations

from datetime import date, datetime

from pydantic import BaseModel, Field


class PlaceStopIn(BaseModel):
    id: str = Field(..., max_length=80)
    name: str = Field(..., max_length=300)
    kind: str | None = Field(None, max_length=16)
    latitude: float
    longitude: float
    arrived_at: datetime
    departed_at: datetime | None = None
    trip_title: str | None = Field(None, max_length=300)


class MovementDayIn(BaseModel):
    day: date
    distance_km: float = Field(..., ge=0)
    vehicle_km: float = Field(..., ge=0)
    away_minutes: int | None = Field(None, ge=0)
    late_away_minutes: int | None = Field(None, ge=0)
    farthest_km: float | None = Field(None, ge=0)
    trip_title: str | None = Field(None, max_length=300)
    # Stops that began on this day; they replace the day's stored stops.
    stops: list[PlaceStopIn] = Field(default_factory=list)


class MovementUploadIn(BaseModel):
    days: list[MovementDayIn] = Field(..., max_length=60)


class MovementUploadOut(BaseModel):
    days: int
    stops: int
