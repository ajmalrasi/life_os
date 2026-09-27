from __future__ import annotations

from datetime import date, timedelta

from fastapi import APIRouter, Depends, HTTPException, Query, Response
from sqlalchemy.orm import Session

from app.api.routes.statements import build_categorizer
from app.database import get_db
from app.schemas.movement import MovementUploadIn, MovementUploadOut
from app.services.movement import delete_all, movement_window, store_days

router = APIRouter(prefix="/movement", tags=["movement"])


@router.put("/days", response_model=MovementUploadOut)
def upload_days(payload: MovementUploadIn, db: Session = Depends(get_db)) -> MovementUploadOut:
    """Idempotent: re-sending a day replaces its summary and stops."""
    days, stops = store_days(db, payload.days)
    return MovementUploadOut(days=days, stops=stops)


@router.get("/days")
def list_days(
    start: date | None = Query(None),
    end: date | None = Query(None),
    db: Session = Depends(get_db),
) -> dict:
    end = end or date.today()
    start = start or end - timedelta(days=6)
    if end < start:
        raise HTTPException(status_code=422, detail="end is before start")
    return movement_window(db, start, end, build_categorizer(db))


@router.delete("", status_code=204, response_class=Response)
def delete_history(db: Session = Depends(get_db)) -> Response:
    delete_all(db)
    return Response(status_code=204)
