from __future__ import annotations

from datetime import date, timedelta

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.database import get_db
from app.repositories.github_repo import GithubRepository
from app.schemas.github import GithubActivityIn, GithubActivityOut

router = APIRouter(prefix="/github", tags=["github"])


@router.post("", response_model=GithubActivityOut)
def submit_activity(payload: GithubActivityIn, db: Session = Depends(get_db)) -> GithubActivityOut:
    """Upsert a day's GitHub / coding activity rollup."""
    obj = GithubRepository(db).upsert(payload)
    return GithubActivityOut.model_validate(obj)


@router.get("", response_model=list[GithubActivityOut])
def list_activity(days: int = 30, db: Session = Depends(get_db)) -> list[GithubActivityOut]:
    end = date.today()
    start = end - timedelta(days=days - 1)
    rows = GithubRepository(db).range(start, end)
    return [GithubActivityOut.model_validate(r) for r in rows]
