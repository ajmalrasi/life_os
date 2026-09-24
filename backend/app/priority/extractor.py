"""Deterministic message facts for Priority: timestamps, previews, and links.

Decisions (importance, category, state, times written in prose) belong to the
model in ``classifier.py``; this module only reads what is unambiguous.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from zoneinfo import ZoneInfo

from app.mail.models import MailMessage

_IST = ZoneInfo("Asia/Kolkata")
_URL = re.compile(r"https?://[^\s<>\"']+", re.I)
_MEETING_DOMAINS = (
    "meet.google.com", "zoom.us", "teams.microsoft.com", "teams.live.com",
    "webex.com", "whereby.com", "skype.com",
)
_SCHEDULING_DOMAINS = ("calendly.com", "cal.com", "goodtime.io", "hirevue.com")


@dataclass(frozen=True)
class ExtractedDetails:
    received_at: datetime
    preview: str
    company: str | None
    role: str | None
    action_text: str | None
    action_url: str | None
    due_at: datetime | None
    event_start: datetime | None
    event_end: datetime | None
    timezone_name: str | None
    location: str | None
    meeting_url: str | None
    calendar_uid: str | None
    has_calendar_invite: bool


@dataclass(frozen=True)
class MessageLinks:
    meeting_url: str | None
    scheduling_url: str | None


def received_at(message: MailMessage) -> datetime:
    if message.internal_date_ms:
        return datetime.fromtimestamp(message.internal_date_ms / 1000, tz=timezone.utc)
    try:
        parsed = parsedate_to_datetime(message.received)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=_IST)
        return parsed.astimezone(timezone.utc)
    except (TypeError, ValueError, OverflowError):
        return datetime.now(timezone.utc)


def preview_text(text: str, limit: int = 800) -> str:
    cleaned = re.sub(r"\s+", " ", text or "").strip()
    cleaned = re.split(r"\b(?:unsubscribe|privacy policy)\b", cleaned, maxsplit=1, flags=re.I)[0]
    return cleaned[:limit]


def links(message: MailMessage) -> MessageLinks:
    urls = [url.rstrip(".,);]") for url in _URL.findall(message.raw_body or message.text)]
    return MessageLinks(
        meeting_url=next(
            (url for url in urls if any(domain in url.lower() for domain in _MEETING_DOMAINS)), None
        ),
        scheduling_url=next(
            (url for url in urls if any(domain in url.lower() for domain in _SCHEDULING_DOMAINS)), None
        ),
    )
