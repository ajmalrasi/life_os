from __future__ import annotations

from datetime import date, datetime, time, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.health import HealthRecord, HealthRecordType
from app.schemas.health import HealthRecordIn


class HealthRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def existing_source_ids(self, source_ids: list[str]) -> set[str]:
        if not source_ids:
            return set()
        rows = self.db.execute(
            select(HealthRecord.source_id).where(HealthRecord.source_id.in_(source_ids))
        ).scalars()
        return set(rows)

    def bulk_insert(self, records: list[HealthRecordIn]) -> int:
        """Insert records, skipping any whose source_id already exists. Returns inserted count."""
        existing = self.existing_source_ids([r.source_id for r in records])
        to_add = [
            HealthRecord(
                source_id=r.source_id,
                type=r.type,
                value=r.value,
                unit=r.unit,
                start_time=r.start_time,
                end_time=r.end_time,
            )
            for r in records
            if r.source_id not in existing
        ]
        self.db.add_all(to_add)
        self.db.commit()
        return len(to_add)

    def for_day(self, day: date, type_: HealthRecordType) -> list[HealthRecord]:
        """Records of a type whose start_time falls within the calendar day."""
        start = datetime.combine(day, time.min)
        end = start + timedelta(days=1)
        stmt = (
            select(HealthRecord)
            .where(
                HealthRecord.type == type_,
                HealthRecord.start_time >= start,
                HealthRecord.start_time < end,
            )
            .order_by(HealthRecord.start_time)
        )
        return list(self.db.execute(stmt).scalars())

    def daily_values(
        self, day: date, type_: HealthRecordType
    ) -> list[float]:
        return [r.value for r in self.for_day(day, type_)]

    def latest_value(self, type_: HealthRecordType, on_or_before: date) -> float | None:
        end = datetime.combine(on_or_before, time.max)
        stmt = (
            select(HealthRecord.value)
            .where(HealthRecord.type == type_, HealthRecord.start_time <= end)
            .order_by(HealthRecord.start_time.desc())
            .limit(1)
        )
        return self.db.execute(stmt).scalar_one_or_none()
