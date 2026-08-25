"""Provider-neutral mail access shared by independent LifeOS consumers."""

from app.mail.gmail_provider import GmailProvider, MailProviderError
from app.mail.models import MailAttachment, MailMessage
from app.mail.provider import MailProvider

__all__ = ["GmailProvider", "MailAttachment", "MailMessage", "MailProvider", "MailProviderError"]
