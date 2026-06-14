from __future__ import annotations

from datetime import date, timedelta

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.database import get_db
from app.repositories.mood_repo import MoodRepository
from app.schemas.mood import MoodCheckinIn, MoodCheckinOut

router = APIRouter(prefix="/mood", tags=["mood"])


@router.post("", response_model=MoodCheckinOut)
def submit_mood(payload: MoodCheckinIn, db: Session = Depends(get_db)) -> MoodCheckinOut:
    """Create or update the mood check-in for a day (one per day)."""
    repo = MoodRepository(db)
    obj = repo.upsert(payload)
    return MoodCheckinOut.model_validate(obj)


@router.get("/{day}", response_model=MoodCheckinOut)
def get_mood(day: date, db: Session = Depends(get_db)) -> MoodCheckinOut:
    obj = MoodRepository(db).get(day)
    if obj is None:
        raise HTTPException(status_code=404, detail="No mood check-in for that day")
    return MoodCheckinOut.model_validate(obj)


@router.get("", response_model=list[MoodCheckinOut])
def list_mood(days: int = 30, db: Session = Depends(get_db)) -> list[MoodCheckinOut]:
    end = date.today()
    start = end - timedelta(days=days - 1)
    rows = MoodRepository(db).range(start, end)
    return [MoodCheckinOut.model_validate(r) for r in rows]
