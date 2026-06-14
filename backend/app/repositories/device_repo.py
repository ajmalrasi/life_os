from __future__ import annotations

from datetime import datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models.device import DeviceToken


class DeviceRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def register(self, token: str, platform: str = "android") -> DeviceToken:
        """Insert a new token or refresh ``last_seen_at`` for an existing one."""
        existing = self.db.execute(
            select(DeviceToken).where(DeviceToken.token == token)
        ).scalar_one_or_none()
        if existing:
            existing.platform = platform
            existing.last_seen_at = datetime.utcnow()
            obj = existing
        else:
            obj = DeviceToken(token=token, platform=platform)
            self.db.add(obj)
        self.db.commit()
        self.db.refresh(obj)
        return obj

    def all_tokens(self) -> list[str]:
        return list(self.db.execute(select(DeviceToken.token)).scalars())

    def delete(self, token: str) -> None:
        obj = self.db.execute(
            select(DeviceToken).where(DeviceToken.token == token)
        ).scalar_one_or_none()
        if obj:
            self.db.delete(obj)
            self.db.commit()
