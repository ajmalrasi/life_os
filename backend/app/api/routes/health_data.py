from __future__ import annotations

from datetime import date, timedelta

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.deps import require_token
from app.database import get_db
from app.repositories.health_repo import HealthRepository
from app.schemas.health import AutoExportIn, HealthBatchIn, IngestResult
from app.services.analytics import AnalyticsService
from app.services.apple_ingest import parse_export

router = APIRouter(prefix="/health", tags=["health"])


@router.post("/batch", response_model=IngestResult, dependencies=[Depends(require_token)])
def ingest_health(payload: HealthBatchIn, db: Session = Depends(get_db)) -> IngestResult:
    """Idempotent batch ingest of Health Connect records (keyed by source_id)."""
    repo = HealthRepository(db)
    inserted = repo.bulk_insert(payload.records)
    received = len(payload.records)
    return IngestResult(received=received, inserted=inserted, skipped=received - inserted)


@router.post("/auto-export", response_model=IngestResult)
def ingest_auto_export(payload: AutoExportIn, db: Session = Depends(get_db)) -> IngestResult:
    """Live ingest of Apple Health data pushed by the iOS app *Health Auto Export*.

    This is how Apple Watch sleep (and other vitals) reach LifeOS, since they
    never make it into Android Health Connect. Idempotent by source_id, and it
    recomputes scores for just the days the payload touched so the dashboard
    reflects new sleep right away.
    """
    records = parse_export(payload.model_dump())
    repo = HealthRepository(db)
    inserted = repo.bulk_insert(records)

    if inserted:
        analytics = AnalyticsService(db)
        # Recompute each touched day plus the day before, so a record near the
        # local/UTC midnight boundary (day-aggregation uses UTC bounds) is never
        # missed. The nightly worker still recomputes the full window regardless.
        days: set[date] = set()
        for r in records:
            d = r.start_time.date()
            days.add(d)
            days.add(d - timedelta(days=1))
        for day in sorted(days):
            analytics.recompute_day(day)
        db.commit()

    received = len(records)
    return IngestResult(received=received, inserted=inserted, skipped=received - inserted)
