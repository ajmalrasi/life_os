from __future__ import annotations

from datetime import datetime, timedelta, timezone
from urllib.parse import quote

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.database import utcnow
from app.models.mail import MailAccount, MailMessageRecord
from app.models.priority import PriorityFeedback, PriorityItem, PriorityItemMessage
from app.priority.classifier import PriorityClassification


def _utc(value: datetime) -> datetime:
    """Normalize SQLite's naive DateTime round-trip and Postgres tz-aware values."""
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)


class PriorityRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def get_for_thread(
        self, account: MailAccount, message_row: MailMessageRecord
    ) -> PriorityItem | None:
        thread_key = message_row.provider_thread_id or f"message:{message_row.provider_message_id}"
        return self.db.execute(
            select(PriorityItem).where(
                PriorityItem.mail_account_id == account.id,
                PriorityItem.thread_key == thread_key,
            )
        ).scalar_one_or_none()

    def latest_messages_for_account(self, account_id: int) -> list[MailMessageRecord]:
        """Return the retained source needed to reclassify every existing item."""
        return list(
            self.db.execute(
                select(MailMessageRecord)
                .join(
                    PriorityItem,
                    PriorityItem.latest_mail_message_id == MailMessageRecord.id,
                )
                .where(PriorityItem.mail_account_id == account_id)
            ).scalars()
        )

    def upsert(
        self,
        account: MailAccount,
        message_row: MailMessageRecord,
        classification: PriorityClassification,
    ) -> tuple[PriorityItem, bool, bool]:
        thread_key = message_row.provider_thread_id or f"message:{message_row.provider_message_id}"
        item = self.get_for_thread(account, message_row)
        created = item is None
        d = classification.details
        values = {
            "latest_mail_message_id": message_row.id,
            "subject": message_row.subject,
            "sender": message_row.sender,
            "company": d.company,
            "role": d.role,
            "importance": classification.importance,
            "automatic_labels": classification.labels,
            "automatic_state": classification.state,
            "confidence": classification.confidence,
            "summary": classification.summary,
            "why_important": classification.why_important,
            "evidence": classification.evidence,
            "action_text": d.action_text,
            "action_url": d.action_url,
            "due_at": d.due_at,
            "event_start": d.event_start,
            "event_end": d.event_end,
            "timezone": d.timezone_name,
            "location": d.location,
            "meeting_url": d.meeting_url,
            "calendar_uid": d.calendar_uid,
            "has_calendar_invite": d.has_calendar_invite,
            "gmail_url": (
                "https://mail.google.com/mail/?authuser="
                f"{quote(account.provider_account_id, safe='@')}#all/{quote(message_row.provider_message_id)}"
            ),
            "preview": d.preview,
            "received_at": d.received_at,
        }
        changed = False
        if item is None:
            item = PriorityItem(mail_account_id=account.id, thread_key=thread_key, **values)
            self.db.add(item)
            self.db.flush()
            changed = True
        elif _utc(d.received_at) >= _utc(item.received_at):
            new_message = item.latest_mail_message_id != message_row.id
            for field, value in values.items():
                if getattr(item, field) != value:
                    setattr(item, field, value)
                    changed = True
            # A genuinely new request or schedule update reopens a previously
            # completed thread. Labels remain user-owned until explicitly reset.
            # Reclassifying the same message (full sync) never undoes the user's choice.
            if (
                new_message
                and item.user_state == "done"
                and classification.state in {"needs_action", "scheduled"}
            ):
                item.user_state = None
                changed = True

        link = self.db.execute(
            select(PriorityItemMessage).where(
                PriorityItemMessage.priority_item_id == item.id,
                PriorityItemMessage.mail_message_id == message_row.id,
            )
        ).scalar_one_or_none()
        if link is None:
            snapshot = {
                "state": classification.state,
                "labels": classification.labels,
                "summary": classification.summary,
                "event_start": d.event_start.isoformat() if d.event_start else None,
                "event_end": d.event_end.isoformat() if d.event_end else None,
                "evidence": classification.evidence,
            }
            self.db.add(
                PriorityItemMessage(
                    priority_item_id=item.id,
                    mail_message_id=message_row.id,
                    classification_snapshot=snapshot,
                )
            )
        self.db.commit()
        self.db.refresh(item)
        return item, created, changed

    def get(self, item_id: int) -> PriorityItem | None:
        return self.db.get(PriorityItem, item_id)

    def account(self, item: PriorityItem) -> MailAccount:
        return self.db.get(MailAccount, item.mail_account_id)

    def latest_message(self, item: PriorityItem) -> MailMessageRecord:
        return self.db.get(MailMessageRecord, item.latest_mail_message_id)

    def list(
        self,
        label: str | None = None,
        state: str | None = None,
        include_done: bool = False,
        limit: int = 200,
        now: datetime | None = None,
        window_days: int | None = None,
    ) -> list[PriorityItem]:
        current = now or datetime.now(timezone.utc)
        oldest = current - timedelta(days=window_days) if window_days else None
        # Priority ordering depends on user overrides, due dates, event dates,
        # and snooze expiry. Select the complete (already-classified) feed before
        # applying that effective ordering so an arbitrary SQL limit cannot hide
        # an urgent item.
        rows = list(self.db.execute(select(PriorityItem)).scalars())
        visible: list[PriorityItem] = []
        expired_snooze = False
        for item in rows:
            if item.effective_importance == "suppressed":
                continue
            if oldest and _utc(item.received_at) < oldest:
                # Older mail stays only while its event or deadline is ahead.
                upcoming = item.event_start or item.due_at
                if upcoming is None or _utc(upcoming) < current:
                    continue
            if item.user_state == "snoozed" and (
                item.snoozed_until is None or _utc(item.snoozed_until) <= current
            ):
                item.user_state = None
                item.snoozed_until = None
                expired_snooze = True
            effective_state = item.state
            if item.snoozed_until and _utc(item.snoozed_until) > current:
                if state != "snoozed":
                    continue
                effective_state = "snoozed"
            if not include_done and effective_state in {"done", "cancelled"}:
                continue
            if not include_done and effective_state == "scheduled" and item.event_end:
                if _utc(item.event_end) < current - timedelta(hours=2):
                    continue
            # A deadline or event that passed over a day ago is no longer
            # something to act on or attend, whatever its state.
            moment = item.event_end or item.event_start or item.due_at
            if not include_done and moment and _utc(moment) < current - timedelta(days=1):
                continue
            if label and label not in item.labels:
                continue
            if state and effective_state != state:
                continue
            visible.append(item)
        if expired_snooze:
            self.db.commit()

        def order(item: PriorityItem):
            state_rank = (
                3
                if item.effective_importance == "review"
                else {"needs_action": 0, "scheduled": 1, "informational": 2}.get(item.state, 4)
            )
            priority_time = (
                _utc(item.due_at or item.event_start)
                if (item.due_at or item.event_start)
                else datetime.max.replace(tzinfo=timezone.utc)
            )
            return state_rank, priority_time, -item.received_at.timestamp()

        return sorted(visible, key=order)[:limit]

    def history(self, item_id: int) -> list[tuple[MailMessageRecord, dict]]:
        return list(
            self.db.execute(
                select(MailMessageRecord, PriorityItemMessage.classification_snapshot)
                .join(PriorityItemMessage, PriorityItemMessage.mail_message_id == MailMessageRecord.id)
                .where(PriorityItemMessage.priority_item_id == item_id)
                .order_by(MailMessageRecord.received_at.desc(), MailMessageRecord.id.desc())
            ).all()
        )

    def apply_update(
        self,
        item: PriorityItem,
        *,
        state: str | None = None,
        labels: list[str] | None = None,
        snoozed_until: datetime | None = None,
    ) -> PriorityItem:
        if state is not None:
            item.user_state = state
            self.db.add(PriorityFeedback(priority_item_id=item.id, kind="state", value={"state": state}))
        if labels is not None:
            item.user_labels = list(dict.fromkeys(labels))
            self.db.add(PriorityFeedback(priority_item_id=item.id, kind="labels", value={"labels": labels}))
        if snoozed_until is not None:
            item.snoozed_until = snoozed_until
            item.user_state = "snoozed"
            self.db.add(
                PriorityFeedback(
                    priority_item_id=item.id,
                    kind="snooze",
                    value={"until": snoozed_until.isoformat()},
                )
            )
        self.db.commit()
        self.db.refresh(item)
        return item

    def feedback(self, item: PriorityItem, kind: str, value: dict) -> PriorityItem:
        if kind == "not_important":
            item.user_state = "done"
        elif kind == "important":
            item.user_importance = "high"
        self.db.add(PriorityFeedback(priority_item_id=item.id, kind=kind, value=value))
        self.db.commit()
        self.db.refresh(item)
        return item
