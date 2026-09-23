from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.database import get_db
from app.schemas.status import SourceStatusOut, SyncStatusOut
from app.services.sync_status import compute_sync_status

router = APIRouter(prefix="/status", tags=["status"])


@router.get("/sync", response_model=SyncStatusOut)
def sync_status(db: Session = Depends(get_db)) -> SyncStatusOut:
    """Per-source sync health: bank mail, statements, Priority mail, sleep/
    vitals, and the local model. Powers the Android "Data sources" panel and
    the backend's own stale-source notification (see AGENTS.md §7, Stage 3).
    """
    return SyncStatusOut(
        sources=[SourceStatusOut(**vars(s)) for s in compute_sync_status(db)]
    )
