from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.database import get_db
from app.repositories.health_repo import HealthRepository
from app.schemas.health import HealthBatchIn, IngestResult

router = APIRouter(prefix="/health", tags=["health"])


@router.post("/batch", response_model=IngestResult)
def ingest_health(payload: HealthBatchIn, db: Session = Depends(get_db)) -> IngestResult:
    """Idempotent batch ingest of Health Connect records (keyed by source_id)."""
    repo = HealthRepository(db)
    inserted = repo.bulk_insert(payload.records)
    received = len(payload.records)
    return IngestResult(received=received, inserted=inserted, skipped=received - inserted)
