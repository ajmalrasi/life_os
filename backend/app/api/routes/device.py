from __future__ import annotations

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.config import get_settings
from app.database import get_db
from app.repositories.device_repo import DeviceRepository

router = APIRouter(prefix="/device", tags=["device"])


class DeviceTokenIn(BaseModel):
    token: str = Field(..., min_length=1, max_length=512)
    platform: str = Field(default="android", max_length=32)


class DeviceTokenOut(BaseModel):
    registered: bool
    push_enabled: bool


@router.post("/token", response_model=DeviceTokenOut)
def register_token(payload: DeviceTokenIn, db: Session = Depends(get_db)) -> DeviceTokenOut:
    """Register (or refresh) an FCM device token so the backend can push to it."""
    DeviceRepository(db).register(payload.token, payload.platform)
    return DeviceTokenOut(registered=True, push_enabled=get_settings().fcm_enabled)


@router.delete("/token", response_model=DeviceTokenOut)
def unregister_token(payload: DeviceTokenIn, db: Session = Depends(get_db)) -> DeviceTokenOut:
    """Remove a token (e.g. on logout / token rotation)."""
    DeviceRepository(db).delete(payload.token)
    return DeviceTokenOut(registered=False, push_enabled=get_settings().fcm_enabled)
