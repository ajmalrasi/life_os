from __future__ import annotations

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.database import get_db
from app.services.notifications import NotificationService

router = APIRouter(prefix="/notifications", tags=["notifications"])


class NotificationOut(BaseModel):
    severity: str
    title: str
    detail: str


@router.get("", response_model=list[NotificationOut])
def get_notifications(db: Session = Depends(get_db)) -> list[NotificationOut]:
    items = NotificationService(db).generate()
    return [NotificationOut(severity=n.severity, title=n.title, detail=n.detail) for n in items]
