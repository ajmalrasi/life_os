from __future__ import annotations

from dataclasses import dataclass, field

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.database import utcnow
from app.models.priority import PriorityItem, PriorityNotification
from app.repositories.device_repo import DeviceRepository
from app.services.fcm import FcmService


@dataclass
class PriorityPush:
    severity: str
    title: str
    detail: str
    data: dict[str, str] = field(default_factory=dict)


class PriorityNotificationService:
    def __init__(self, db: Session) -> None:
        self.db = db

    def push_new(self, items: list[PriorityItem]) -> None:
        tokens = DeviceRepository(self.db).all_tokens()
        fcm = FcmService()
        if not tokens or not fcm.enabled:
            return
        for item in items:
            if item.effective_importance != "high" or item.state not in {"needs_action", "scheduled"}:
                continue
            event_key = f"new:{item.latest_mail_message_id}:{item.automatic_state}"
            existing = self.db.execute(
                select(PriorityNotification).where(
                    PriorityNotification.priority_item_id == item.id,
                    PriorityNotification.event_key == event_key,
                )
            ).scalar_one_or_none()
            if existing:
                continue
            title = item.subject or "Important email"
            detail = item.summary
            result = fcm.push_notifications(
                [
                    PriorityPush(
                        severity="warning",
                        title=title,
                        detail=detail,
                        data={
                            "body": detail,
                            "open_tab": "priority",
                            "priority_item_id": str(item.id),
                        },
                    )
                ],
                tokens,
            )
            if result.delivered:
                self.db.add(
                    PriorityNotification(
                        priority_item_id=item.id,
                        event_key=event_key,
                        delivered_at=utcnow(),
                    )
                )
                self.db.commit()
