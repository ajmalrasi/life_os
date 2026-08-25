from __future__ import annotations

from sqlalchemy.orm import Session

from app.config import Settings
from app.mail.gmail_provider import GmailProvider
from app.repositories.mail_repo import MailAccountRepository


def provider_for_address(settings: Settings, address: str) -> GmailProvider:
    return GmailProvider(
        account=address,
        keyring_password=settings.gog_keyring_password,
        gog_bin=settings.gog_bin,
        home=settings.gog_home or None,
    )


def finance_provider(db: Session, settings: Settings) -> GmailProvider:
    account = MailAccountRepository(db).finance_account(settings.gmail_account)
    return provider_for_address(settings, account.provider_account_id if account else "")
