"""Health Connect records.

A single generic table stores every health metric type as a typed row.
Keeping raw historical values lets the analytics engine recompute scores
whenever scoring logic changes, without re-ingesting from the device.
"""

from __future__ import annotations

import enum
from datetime import datetime

from sqlalchemy import DateTime, Float, Index, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base, utcnow


class HealthRecordType(str, enum.Enum):
    """Metric types the scoring/analytics engine knows how to interpret.

    This enum is **not** an exhaustive whitelist of what may be stored. The
    ``type`` column is a free-form string so any health signal — including
    metrics no part of LifeOS scores yet (e.g. physical effort, daylight time,
    walking speed, AFib burden) — can be captured now and used later, in
    keeping with the "keep raw history, recompute scores when logic changes"
    design. These members are simply the well-known types used by name in
    scoring/analytics/queries; ingestion accepts arbitrary type strings.
    """

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
    CALORIES = "calories"                      # kcal (total)
    ACTIVE_CALORIES = "active_calories"        # kcal (active)
    BASAL_METABOLIC_RATE = "basal_metabolic_rate"  # kcal/day
    WALKING_DISTANCE = "walking_distance"      # meters
    ELEVATION_GAINED = "elevation_gained"      # meters
    FLOORS_CLIMBED = "floors_climbed"          # count
    VO2_MAX = "vo2_max"                        # ml/kg/min
    RESPIRATORY_RATE = "respiratory_rate"      # breaths/min
    BLOOD_PRESSURE_SYSTOLIC = "blood_pressure_systolic"    # mmHg
    BLOOD_PRESSURE_DIASTOLIC = "blood_pressure_diastolic"  # mmHg
    BLOOD_GLUCOSE = "blood_glucose"            # mg/dL
    BODY_TEMPERATURE = "body_temperature"      # celsius
    BASAL_BODY_TEMPERATURE = "basal_body_temperature"  # celsius
    HEIGHT = "height"                          # meters
    BODY_FAT = "body_fat"                      # %
    LEAN_BODY_MASS = "lean_body_mass"          # kg
    BONE_MASS = "bone_mass"                    # kg
    BODY_WATER_MASS = "body_water_mass"        # kg
    HYDRATION = "hydration"                    # liters


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
    # Free-form metric type. Values typically come from HealthRecordType, but the
    # column intentionally accepts any string so new signals are never dropped.
    type: Mapped[str] = mapped_column(String(64))
    value: Mapped[float] = mapped_column(Float)
    unit: Mapped[str] = mapped_column(String(32))
    # What kind of record this is beyond its type, e.g. a workout's activity
    # ("Traditional Strength Training"). Null for plain metric readings.
    label: Mapped[str | None] = mapped_column(String(64), nullable=True)
    start_time: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    end_time: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
