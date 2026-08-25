from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.database import get_db
from app.models.mail import MailSyncState
from app.repositories.mail_repo import MailAccountRepository
from app.schemas.priority import MailAccountOut

router = APIRouter(prefix="/mail/accounts", tags=["mail"])


@router.get("", response_model=list[MailAccountOut])
def list_mail_accounts(db: Session = Depends(get_db)) -> list[MailAccountOut]:
    repo = MailAccountRepository(db)
    out: list[MailAccountOut] = []
    for account in repo.all():
        state = db.execute(
            select(MailSyncState).where(
                MailSyncState.mail_account_id == account.id,
                MailSyncState.consumer == "priority",
            )
        ).scalar_one_or_none()
        out.append(
            MailAccountOut(
                id=account.id,
                provider=account.provider,
                address=account.provider_account_id,
                display_label=account.display_label,
                purposes=repo.purposes(account.id),
                enabled=account.enabled,
                priority_last_success_at=state.last_success_at if state else None,
                priority_last_error=state.last_error if state else None,
            )
        )
    return out
