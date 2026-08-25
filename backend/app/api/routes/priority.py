from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.database import get_db
from app.models.priority import PriorityItem
from app.priority.service import PrioritySyncService
from app.priority.notifications import PriorityNotificationService
from app.repositories.priority_repo import PriorityRepository
from app.schemas.priority import (
    PriorityDetailsOut,
    PriorityFeedbackIn,
    PriorityHistoryOut,
    PriorityItemOut,
    PrioritySyncOut,
    PriorityUpdate,
)

router = APIRouter(prefix="/priority", tags=["priority"])


def _out(repo: PriorityRepository, item: PriorityItem) -> PriorityItemOut:
    account = repo.account(item)
    return PriorityItemOut(
        id=item.id,
        subject=item.subject,
        sender=item.sender,
        company=item.company,
        role=item.role,
        importance=item.effective_importance,
        labels=item.labels,
        state=item.state,
        confidence=item.confidence,
        summary=item.summary,
        why_important=item.why_important,
        evidence=item.evidence,
        action_text=item.action_text,
        action_url=item.action_url,
        due_at=item.due_at,
        event_start=item.event_start,
        event_end=item.event_end,
        timezone=item.timezone,
        location=item.location,
        meeting_url=item.meeting_url,
        calendar_uid=item.calendar_uid,
        has_calendar_invite=item.has_calendar_invite,
        gmail_url=item.gmail_url,
        preview=item.preview,
        received_at=item.received_at,
        snoozed_until=item.snoozed_until,
        account_label=account.display_label,
        account_address=account.provider_account_id,
    )


@router.get("", response_model=list[PriorityItemOut])
def list_priority(
    label: str | None = None,
    state: str | None = None,
    include_done: bool = False,
    limit: int = Query(default=100, ge=1, le=500),
    db: Session = Depends(get_db),
) -> list[PriorityItemOut]:
    repo = PriorityRepository(db)
    return [_out(repo, item) for item in repo.list(label, state, include_done, limit)]


@router.get("/{item_id}", response_model=PriorityDetailsOut)
def priority_details(item_id: int, db: Session = Depends(get_db)) -> PriorityDetailsOut:
    repo = PriorityRepository(db)
    item = repo.get(item_id)
    if item is None:
        raise HTTPException(status_code=404, detail="Priority item not found")
    history = [
        PriorityHistoryOut(
            message_id=message.provider_message_id,
            subject=message.subject,
            sender=message.sender,
            received_at=message.received_at,
            preview=message.preview,
            classification=snapshot,
        )
        for message, snapshot in repo.history(item_id)
    ]
    return PriorityDetailsOut(item=_out(repo, item), history=history)


@router.patch("/{item_id}", response_model=PriorityItemOut)
def update_priority(
    item_id: int, payload: PriorityUpdate, db: Session = Depends(get_db)
) -> PriorityItemOut:
    repo = PriorityRepository(db)
    item = repo.get(item_id)
    if item is None:
        raise HTTPException(status_code=404, detail="Priority item not found")
    updated = repo.apply_update(
        item, state=payload.state, labels=payload.labels, snoozed_until=payload.snoozed_until
    )
    return _out(repo, updated)


@router.post("/{item_id}/feedback", response_model=PriorityItemOut)
def priority_feedback(
    item_id: int, payload: PriorityFeedbackIn, db: Session = Depends(get_db)
) -> PriorityItemOut:
    repo = PriorityRepository(db)
    item = repo.get(item_id)
    if item is None:
        raise HTTPException(status_code=404, detail="Priority item not found")
    return _out(repo, repo.feedback(item, payload.kind, payload.value))


@router.post("/sync/check", response_model=PrioritySyncOut)
def sync_priority(full: bool = False, db: Session = Depends(get_db)) -> PrioritySyncOut:
    report = PrioritySyncService(db).sync(full=full)
    PriorityNotificationService(db).push_new(report.new_items)
    repo = PriorityRepository(db)
    return PrioritySyncOut(
        successful=not report.account_errors,
        accounts_run=report.accounts_run,
        emails_found=report.emails_found,
        emails_processed=report.emails_processed,
        items_new=report.items_new,
        items_updated=report.items_updated,
        items_review=report.items_review,
        pending=report.pending,
        message=report.message,
        account_errors=report.account_errors,
        new_items=[_out(repo, item) for item in report.new_items],
    )
