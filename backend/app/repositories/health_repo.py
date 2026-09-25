from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, time, timedelta

from sqlalchemy import case, func, literal, select
from sqlalchemy.orm import Session

from app.models.health import HealthRecord, HealthRecordType
from app.database import utcnow
from app.schemas.health import HealthRecordIn

_SLEEP_TYPES = (
    HealthRecordType.SLEEP_DURATION.value,
    HealthRecordType.SLEEP_DEEP.value,
    HealthRecordType.SLEEP_REM.value,
    HealthRecordType.SLEEP_AWAKE.value,
)


@dataclass
class SleepNight:
    """The sleep attributed to one wake-up day, after merging revisions."""

    total_hours: float
    deep_hours: float | None
    rem_hours: float | None
    awake_hours: float | None
    start: datetime
    end: datetime


def _is_revisable(r: HealthRecord) -> bool:
    # Health Auto Export sends a day's aggregate again whenever it changes, so
    # two hae rows of one type with the same time span are two versions of the
    # same reading. Import (apple-) rows are raw samples and stay distinct.
    return r.source_id.startswith("hae-")


def _same_time(a: datetime, b: datetime) -> bool:
    # SQLite (tests) hands back naive wall-clock times; compare like with like.
    if (a.tzinfo is None) != (b.tzinfo is None):
        return a.replace(tzinfo=None) == b.replace(tzinfo=None)
    return a == b


def _latest_revisions(rows: list[HealthRecord]) -> list[HealthRecord]:
    """Keep only the newest version of each re-sent reading (highest id wins)."""
    latest: dict[tuple, HealthRecord] = {}
    kept: list[HealthRecord] = []
    for r in rows:
        if not _is_revisable(r):
            kept.append(r)
            continue
        key = (r.type, r.start_time, r.end_time)
        if key not in latest or r.id > latest[key].id:
            latest[key] = r
    return sorted(kept + list(latest.values()), key=lambda r: r.start_time)


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
        """Insert records, skipping any whose source_id already exists. Returns inserted count.

        Dedupes both against the DB and *within* the incoming batch — a single
        Apple Health reading is often recorded by several source devices, so the
        same source_id can appear twice in one payload; adding both would violate
        the unique constraint and fail the whole batch.
        """
        existing = self.existing_source_ids([r.source_id for r in records])
        seen: set[str] = set()
        to_add: list[HealthRecord] = []
        for r in records:
            if r.source_id in existing or r.source_id in seen:
                continue
            seen.add(r.source_id)
            to_add.append(
                HealthRecord(
                    source_id=r.source_id,
                    type=r.type,
                    value=r.value,
                    unit=r.unit,
                    start_time=r.start_time,
                    end_time=r.end_time,
                )
            )
        self.db.add_all(to_add)
        self.db.commit()
        return len(to_add)

    def upsert_records(self, records: list[HealthRecordIn]) -> tuple[int, int]:
        """Insert new readings and overwrite changed ones. Returns (inserted, updated).

        For the live Health Auto Export push, where the same reading (same
        source_id) is re-sent with a corrected value as the day goes on. The
        newest payload wins, and ``created_at`` moves forward so it keeps
        meaning "when this value last arrived".
        """
        latest: dict[str, HealthRecordIn] = {}
        for r in records:
            latest[r.source_id] = r
        existing: dict[str, HealthRecord] = {}
        ids = list(latest)
        for i in range(0, len(ids), 500):
            rows = self.db.execute(
                select(HealthRecord).where(HealthRecord.source_id.in_(ids[i : i + 500]))
            ).scalars()
            existing.update({row.source_id: row for row in rows})

        inserted = updated = 0
        for sid, r in latest.items():
            row = existing.get(sid)
            if row is None:
                self.db.add(HealthRecord(
                    source_id=sid, type=r.type, value=r.value, unit=r.unit,
                    start_time=r.start_time, end_time=r.end_time,
                ))
                inserted += 1
            elif (
                row.value != r.value
                or not _same_time(row.start_time, r.start_time)
                or not _same_time(row.end_time, r.end_time)
            ):
                row.value, row.unit = r.value, r.unit
                row.start_time, row.end_time = r.start_time, r.end_time
                row.created_at = utcnow()
                updated += 1
        self.db.commit()
        return inserted, updated

    def for_day(self, day: date, type_: HealthRecordType) -> list[HealthRecord]:
        """Records of a type whose start_time falls within the calendar day.

        Re-sent versions of the same reading collapse to the newest one.
        """
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
        return _latest_revisions(list(self.db.execute(stmt).scalars()))

    def sleep_for_day(self, day: date) -> SleepNight | None:
        """Sleep that ended on ``day`` (the wake-up day), or None if none was recorded.

        A night is filed under the morning you woke up, so a 23:30 bedtime and
        a 02:00 bedtime both count as the same night. Each session is stored as
        rows sharing one (start, end) span: duration plus deep/REM/awake stages.
        Sessions that overlap are versions of the same sleep (Health Auto
        Export re-sending a growing night, or the one-time import and the live
        push both covering it), so each overlapping group contributes only its
        longest version. Separate, non-overlapping sessions such as a nap add up.
        """
        start = datetime.combine(day, time.min)
        stmt = select(HealthRecord).where(
            HealthRecord.type.in_(_SLEEP_TYPES),
            HealthRecord.end_time >= start,
            HealthRecord.end_time < start + timedelta(days=1),
        )
        sessions: dict[tuple[datetime, datetime], dict[str, float]] = {}
        for r in _latest_revisions(list(self.db.execute(stmt).scalars())):
            sessions.setdefault((r.start_time, r.end_time), {})[r.type] = r.value
        spans = sorted(
            (span, stages) for span, stages in sessions.items()
            if HealthRecordType.SLEEP_DURATION.value in stages
        )
        if not spans:
            return None

        groups: list[list[tuple[tuple[datetime, datetime], dict[str, float]]]] = []
        group_end: datetime | None = None
        for span, stages in spans:
            if group_end is not None and span[0] < group_end:
                groups[-1].append((span, stages))
                group_end = max(group_end, span[1])
            else:
                groups.append([(span, stages)])
                group_end = span[1]
        chosen = [
            max(g, key=lambda item: item[1][HealthRecordType.SLEEP_DURATION.value]) for g in groups
        ]

        def total(t: HealthRecordType) -> float | None:
            vals = [stages[t.value] for _, stages in chosen if t.value in stages]
            return sum(vals) if vals else None

        return SleepNight(
            total_hours=total(HealthRecordType.SLEEP_DURATION) or 0.0,
            deep_hours=total(HealthRecordType.SLEEP_DEEP),
            rem_hours=total(HealthRecordType.SLEEP_REM),
            awake_hours=total(HealthRecordType.SLEEP_AWAKE),
            start=min(span[0] for span, _ in chosen),
            end=max(span[1] for span, _ in chosen),
        )

    def last_received_at(self) -> datetime | None:
        """When the live Health Auto Export push last delivered a new or changed value."""
        return self.db.execute(
            select(func.max(HealthRecord.created_at)).where(HealthRecord.source_id.like("hae-%"))
        ).scalar_one_or_none()

    def daily_values(
        self, day: date, type_: HealthRecordType
    ) -> list[float]:
        return [r.value for r in self.for_day(day, type_)]

    def baseline_mean(
        self, start: date, end: date, type_: HealthRecordType
    ) -> float | None:
        """Mean of per-day means for ``type_`` over ``[start, end)`` in one query.

        Equivalent to averaging each day's ``daily_values`` mean across the
        window, but collapses what used to be one query per day (the recovery
        baselines dominated recompute time once the table grew to ~1M+ rows).
        """
        # Same "newest version wins" rule as for_day: re-sent hae readings share a
        # partition, every other row is its own partition.
        version_of = case(
            (HealthRecord.source_id.like("hae-%"), literal("hae")),
            else_=HealthRecord.source_id,
        )
        versions = (
            select(
                HealthRecord.start_time.label("start_time"),
                HealthRecord.value.label("value"),
                func.row_number()
                .over(
                    partition_by=(version_of, HealthRecord.start_time, HealthRecord.end_time),
                    order_by=HealthRecord.id.desc(),
                )
                .label("rn"),
            )
            .where(
                HealthRecord.type == type_,
                HealthRecord.start_time >= datetime.combine(start, time.min),
                HealthRecord.start_time < datetime.combine(end, time.min),
            )
            .subquery()
        )
        per_day = (
            select(func.avg(versions.c.value).label("day_mean"))
            .where(versions.c.rn == 1)
            .group_by(func.date(versions.c.start_time))
            .subquery()
        )
        return self.db.execute(select(func.avg(per_day.c.day_mean))).scalar_one_or_none()

    def latest_value(self, type_: HealthRecordType, on_or_before: date) -> float | None:
        end = datetime.combine(on_or_before, time.max)
        stmt = (
            select(HealthRecord.value)
            .where(HealthRecord.type == type_, HealthRecord.start_time <= end)
            .order_by(HealthRecord.start_time.desc())
            .limit(1)
        )
        return self.db.execute(stmt).scalar_one_or_none()
