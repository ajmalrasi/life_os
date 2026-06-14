"""Health Connect records.

A single generic table stores every health metric type as a typed row.
Keeping raw historical values lets the analytics engine recompute scores
whenever scoring logic changes, without re-ingesting from the device.
"""

from __future__ import annotations

import enum
from datetime import datetime

from sqlalchemy import DateTime, Enum, Float, Index, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


class HealthRecordType(str, enum.Enum):
    """Supported Health Connect metric types (Phase 1 + forward-compatible)."""

    SLEEP_DURATION = "sleep_duration"          # hours
    SLEEP_DEEP = "sleep_deep"                  # hours
    SLEEP_REM = "sleep_rem"                    # hours
    SLEEP_LIGHT = "sleep_light"                # hours
    SLEEP_AWAKE = "sleep_awake"                # hours
    HEART_RATE = "heart_rate"                  # bpm
    RESTING_HEART_RATE = "resting_heart_rate"  # bpm
    HRV = "hrv"                                # ms
    BLOOD_OXYGEN = "blood_oxygen"              # %
    WEIGHT = "weight"                          # kg
    STEPS = "steps"                            # count
    WORKOUT = "workout"                        # minutes
    CALORIES = "calories"                      # kcal
    WALKING_DISTANCE = "walking_distance"      # meters
    VO2_MAX = "vo2_max"                        # ml/kg/min


class HealthRecord(Base):
    __tablename__ = "health_records"
    __table_args__ = (
        # The Android client supplies a stable source_id per Health Connect
        # record so re-syncs are idempotent.
        UniqueConstraint("source_id", name="uq_health_source_id"),
        Index("ix_health_type_start", "type", "start_time"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    source_id: Mapped[str] = mapped_column(String(128))
    type: Mapped[HealthRecordType] = mapped_column(Enum(HealthRecordType, native_enum=False))
    value: Mapped[float] = mapped_column(Float)
    unit: Mapped[str] = mapped_column(String(32))
    start_time: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    end_time: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=datetime.utcnow)
