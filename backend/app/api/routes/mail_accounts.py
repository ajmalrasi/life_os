from __future__ import annotations

import hashlib
import secrets
import uuid
from datetime import datetime, timedelta, timezone
from urllib.parse import parse_qs, urlsplit

from fastapi import APIRouter, Depends, HTTPException, Response, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.database import get_db
from app.mail.oauth import GogOAuthService, MailAuthorizationError
from app.models.mail import MailAccount, MailAuthSession, MailSyncState
from app.repositories.mail_repo import MailAccountRepository, MailAuthSessionRepository
from app.schemas.priority import (
    MailAccountOut,
    MailConnectionCompleteIn,
    MailConnectionStartIn,
    MailConnectionStartOut,
)

router = APIRouter(prefix="/mail/accounts", tags=["mail"])


def _account_out(
    db: Session,
    repo: MailAccountRepository,
    account: MailAccount,
    authorized_accounts: set[str],
) -> MailAccountOut:
    sync_state = db.execute(
        select(MailSyncState).where(
            MailSyncState.mail_account_id == account.id,
            MailSyncState.consumer == "priority",
        )
    ).scalar_one_or_none()
    return MailAccountOut(
        id=account.id,
        provider=account.provider,
        address=account.provider_account_id,
        display_label=account.display_label,
        purposes=repo.purposes(account.id),
        enabled=account.enabled,
        authorized=account.provider_account_id.casefold() in authorized_accounts,
        priority_last_success_at=sync_state.last_success_at if sync_state else None,
        priority_last_error=sync_state.last_error if sync_state else None,
    )


def _secret_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _validate_callback(session: MailAuthSession, callback_url: str) -> None:
    expected = urlsplit(session.redirect_uri)
    actual = urlsplit(callback_url)
    if (
        actual.scheme != expected.scheme
        or actual.hostname != expected.hostname
        or actual.port != expected.port
        or actual.path != expected.path
        or actual.fragment
    ):
        raise HTTPException(status_code=400, detail="Google returned an invalid callback address.")
    params = parse_qs(actual.query)
    if params.get("error"):
        raise HTTPException(status_code=400, detail="Google authorization was cancelled.")
    if not params.get("code") or not params.get("state"):
        raise HTTPException(status_code=400, detail="Google callback is missing authorization data.")
    if params["state"] != [session.id]:
        raise HTTPException(status_code=400, detail="Google returned an invalid authorization state.")


@router.get("", response_model=list[MailAccountOut])
def list_mail_accounts(db: Session = Depends(get_db)) -> list[MailAccountOut]:
    repo = MailAccountRepository(db)
    # Convert the old environment-only Money connection into the registry once.
    # A deliberately disabled assignment is never bootstrapped again.
    repo.finance_account(get_settings().gmail_account)
    authorized = GogOAuthService().authorized_accounts()
    return [_account_out(db, repo, account, authorized) for account in repo.all()]


@router.post("/connect/start", response_model=MailConnectionStartOut)
def start_mail_connection(
    payload: MailConnectionStartIn,
    db: Session = Depends(get_db),
) -> MailConnectionStartOut:
    accounts = MailAccountRepository(db)
    oauth = GogOAuthService()
    label = (payload.display_label or ("Money inbox" if payload.purpose == "finance" else "Priority inbox"))
    label = label.strip()[:80]
    authorized = oauth.authorized_accounts()
    if payload.email and payload.email in authorized and not payload.force_reauthorize:
        account = accounts.assign_exclusive(payload.email, payload.purpose, label)
        return MailConnectionStartOut(
            requires_authorization=False,
            account=_account_out(db, accounts, account, authorized),
        )

    redirect_uri = f"http://127.0.0.1:{payload.callback_port}/oauth2/callback"
    session_id = str(uuid.uuid4())
    session_secret = secrets.token_urlsafe(64)
    try:
        remote = oauth.start(
            redirect_uri,
            session_id,
            session_secret,
            payload.email if payload.force_reauthorize else None,
        )
    except MailAuthorizationError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc

    expires_at = datetime.now(timezone.utc) + timedelta(minutes=5)
    MailAuthSessionRepository(db).create(
        session_id=session_id,
        purpose=payload.purpose,
        email=payload.email or "",
        display_label=label,
        redirect_uri=redirect_uri,
        secret_hash=_secret_hash(session_secret),
        expires_at=expires_at,
    )
    return MailConnectionStartOut(
        requires_authorization=True,
        session_id=session_id,
        session_secret=session_secret,
        authorization_url=remote.authorization_url,
        expires_at=expires_at,
    )


@router.post("/connect/complete", response_model=MailAccountOut)
def complete_mail_connection(
    payload: MailConnectionCompleteIn,
    db: Session = Depends(get_db),
) -> MailAccountOut:
    sessions = MailAuthSessionRepository(db)
    session = sessions.get_pending(payload.session_id)
    if session is None or not secrets.compare_digest(
        session.secret_hash, _secret_hash(payload.session_secret)
    ):
        raise HTTPException(status_code=404, detail="This authorization session expired.")
    try:
        _validate_callback(session, payload.callback_url)
        email = GogOAuthService().complete(
            session.expected_email or None,
            session.redirect_uri,
            payload.callback_url,
            payload.session_secret,
        )
    except HTTPException as exc:
        sessions.fail(session, str(exc.detail))
        raise
    except MailAuthorizationError as exc:
        sessions.fail(session, str(exc))
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    accounts = MailAccountRepository(db)
    account = accounts.assign_exclusive(email, session.purpose, session.display_label)
    sessions.complete(session)
    return _account_out(db, accounts, account, {email})


@router.delete("/{account_id}/purposes/{purpose}", status_code=status.HTTP_204_NO_CONTENT)
def stop_using_mail_account(
    account_id: int,
    purpose: str,
    db: Session = Depends(get_db),
) -> Response:
    if purpose not in MailAccountRepository.PURPOSES:
        raise HTTPException(status_code=400, detail="Unsupported mail purpose.")
    if not MailAccountRepository(db).disable_purpose(account_id, purpose):
        raise HTTPException(status_code=404, detail="Mail connection not found.")
    return Response(status_code=status.HTTP_204_NO_CONTENT)
