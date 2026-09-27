from __future__ import annotations

import threading
from datetime import date, timedelta

from fastapi import APIRouter, BackgroundTasks, Depends
from sqlalchemy.orm import Session

from app.api.deps import require_token
from app.api.routes.analytics import refresh_day_brief_bg
from app.database import get_db
from app.repositories.health_repo import HealthRepository
from app.schemas.health import AutoExportIn, HealthBatchIn, HealthRecordIn, IngestResult
from app.services.analytics import AnalyticsService
from app.services.apple_ingest import parse_export

router = APIRouter(prefix="/health", tags=["health"])

# Health Auto Export runs one automation per data type (metrics, workouts) and
# they often fire in the same second. Each push upserts records and recomputes
# the same days, so pushes are handled one at a time.
_AUTO_EXPORT_LOCK = threading.Lock()


@router.post("/batch", response_model=IngestResult, dependencies=[Depends(require_token)])
def ingest_health(payload: HealthBatchIn, db: Session = Depends(get_db)) -> IngestResult:
    """Idempotent batch ingest of Health Connect records (keyed by source_id)."""
    repo = HealthRepository(db)
    inserted = repo.bulk_insert(payload.records)
    received = len(payload.records)
    return IngestResult(received=received, inserted=inserted, skipped=received - inserted)


@router.post("/auto-export", response_model=IngestResult)
def ingest_auto_export(
    payload: AutoExportIn,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
) -> IngestResult:
    """Live ingest of Apple Health data pushed by the iOS app *Health Auto Export*.

    This is how Apple Watch sleep (and other vitals) reach LifeOS, since they
    never make it into Android Health Connect. A re-sent reading replaces the
    stored one (same source_id). Scores are recomputed for just the days the
    payload touched, and today's brief is refreshed in the background.
    """
    records = parse_export(payload.model_dump())
    with _AUTO_EXPORT_LOCK:
        inserted, updated = _store_auto_export(db, payload, records)
    if inserted or updated:
        background_tasks.add_task(refresh_day_brief_bg)

    received = len(records)
    return IngestResult(
        received=received,
        inserted=inserted,
        updated=updated,
        skipped=received - inserted - updated,
    )


def _store_auto_export(db: Session, payload: AutoExportIn, records: list[HealthRecordIn]) -> tuple[int, int]:
    repo = HealthRepository(db)
    inserted, updated = repo.upsert_records(records)
    if "workouts" in payload.data.model_fields_set:
        # A workouts export arrived, even an empty one: from here on, no
        # workout on a day means none was recorded, not that none was sent.
        repo.mark_workout_export()

    if inserted or updated:
        analytics = AnalyticsService(db)
        # Sleep is filed under its wake-up day (end_time); everything else
        # under its start day. Include the day before as well, so a reading
        # near midnight is never missed. The nightly worker recomputes the
        # full window regardless.
        days: set[date] = set()
        for r in records:
            d = r.start_time.date()
            days.update({d, d - timedelta(days=1), r.end_time.date()})
        for day in sorted(days):
            analytics.recompute_day(day)
        db.commit()
    return inserted, updated
