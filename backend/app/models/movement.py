"""Places: where the user went each day, uploaded by the phone.

Only per-day summaries and stops are stored here. The raw route stays on the
phone. Stop coordinates are rounded to 3 decimal places (about 100 m), which
is enough to tell places apart without keeping an exact trail.
"""

from __future__ import annotations

from datetime import date, datetime

from sqlalchemy import Date, DateTime, Float, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base, utcnow


class MovementDay(Base):
    __tablename__ = "movement_days"

    day: Mapped[date] = mapped_column(Date, primary_key=True)
    distance_km: Mapped[float] = mapped_column(Float, default=0.0)
    # Distance covered at vehicle speed (descriptive only, not a verdict).
    vehicle_km: Mapped[float] = mapped_column(Float, default=0.0)
    # Null until the user saves Home on the phone.
    away_minutes: Mapped[int | None] = mapped_column(Integer, default=None)
    late_away_minutes: Mapped[int | None] = mapped_column(Integer, default=None)
    farthest_km: Mapped[float | None] = mapped_column(Float, default=None)
    stop_count: Mapped[int] = mapped_column(Integer, default=0)
    # Set when a tracked trip covered this day: its travel was planned.
    trip_title: Mapped[str | None] = mapped_column(String(300), default=None)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)


class PlaceStop(Base):
    __tablename__ = "place_stops"

    id: Mapped[str] = mapped_column(String(80), primary_key=True)
    day: Mapped[date] = mapped_column(Date, index=True)
    name: Mapped[str] = mapped_column(String(300))
    # "home", "work", or null.
    kind: Mapped[str | None] = mapped_column(String(16), default=None)
    latitude: Mapped[float] = mapped_column(Float)
    longitude: Mapped[float] = mapped_column(Float)
    arrived_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    departed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), default=None)
    trip_title: Mapped[str | None] = mapped_column(String(300), default=None)
