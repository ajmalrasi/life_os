from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from email.utils import parseaddr, parsedate_to_datetime
from zoneinfo import ZoneInfo

from dateutil import parser as date_parser

from app.mail.models import MailMessage
from app.priority.calendar import CalendarInvite

_IST = ZoneInfo("Asia/Kolkata")
_URL = re.compile(r"https?://[^\s<>\"']+", re.I)
_MEETING_DOMAINS = (
    "meet.google.com", "zoom.us", "teams.microsoft.com", "teams.live.com",
    "webex.com", "whereby.com", "skype.com",
)
_SCHEDULING_DOMAINS = ("calendly.com", "cal.com", "goodtime.io", "hirevue.com", "schedule")
_MONTH = r"(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)"
_DATE_TIME = re.compile(
    rf"(?:Mon(?:day)?|Tue(?:sday)?|Wed(?:nesday)?|Thu(?:rsday)?|Fri(?:day)?|Sat(?:urday)?|Sun(?:day)?)?,?\s*"
    rf"(?:{_MONTH}\s+\d{{1,2}}(?:st|nd|rd|th)?|\d{{1,2}}(?:st|nd|rd|th)?\s+{_MONTH})"
    rf"(?:,?\s+\d{{4}})?(?:\s+(?:at\s+)?\d{{1,2}}(?::\d{{2}})?\s*(?:am|pm))?"
    rf"(?:\s+(?:IST|UTC|GMT|PST|PDT|EST|EDT))?",
    re.I,
)
_NUMERIC_DATE_TIME = re.compile(
    r"\b\d{1,2}[/-]\d{1,2}[/-]\d{2,4}(?:\s+(?:at\s+)?\d{1,2}(?::\d{2})?\s*(?:am|pm)?)?(?:\s+(?:IST|UTC|GMT))?\b",
    re.I,
)
_TIME_ONLY = re.compile(r"\b(?:at\s+)?(\d{1,2}(?::\d{2})?\s*(?:am|pm))\b", re.I)
_TZINFOS = {"IST": 19800, "UTC": 0, "GMT": 0, "PST": -28800, "PDT": -25200, "EST": -18000, "EDT": -14400}


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


def _parse_candidate(candidate: str, base: datetime) -> datetime | None:
    try:
        default = base.astimezone(_IST).replace(second=0, microsecond=0)
        parsed = date_parser.parse(
            re.sub(r"(?<=\d)(?:st|nd|rd|th)\b", "", candidate, flags=re.I),
            fuzzy=True,
            dayfirst=False,
            default=default.replace(tzinfo=None),
            tzinfos=_TZINFOS,
        )
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=_IST)
        if parsed.year < base.year - 1:
            parsed = parsed.replace(year=base.year)
        return parsed.astimezone(timezone.utc)
    except (ValueError, OverflowError):
        return None


def _find_datetime(text: str, base: datetime) -> datetime | None:
    tomorrow = re.search(r"\btomorrow\b.{0,24}?" + _TIME_ONLY.pattern, text, re.I)
    if tomorrow:
        clock = date_parser.parse(tomorrow.group(1)).time()
        local = base.astimezone(_IST) + timedelta(days=1)
        return local.replace(hour=clock.hour, minute=clock.minute, second=0, microsecond=0).astimezone(timezone.utc)
    for pattern in (_DATE_TIME, _NUMERIC_DATE_TIME):
        for match in pattern.finditer(text):
            if not re.search(r"\d{1,2}(?::\d{2})?\s*(?:am|pm)", match.group(0), re.I):
                continue
            parsed = _parse_candidate(match.group(0), base)
            if parsed:
                return parsed
    return None


def _due_datetime(text: str, base: datetime) -> datetime | None:
    match = re.search(r"\b(?:by|before|deadline[:\s]+)\s+(.{3,70})", text, re.I)
    if not match:
        return None
    segment = match.group(1).splitlines()[0]
    candidate_match = _DATE_TIME.search(segment) or _NUMERIC_DATE_TIME.search(segment)
    if candidate_match:
        return _parse_candidate(candidate_match.group(0), base)
    if re.match(r"tomorrow\b", segment, re.I):
        local = (base.astimezone(_IST) + timedelta(days=1)).replace(hour=23, minute=59, second=0)
        return local.astimezone(timezone.utc)
    return None


def _duration(text: str) -> timedelta:
    match = re.search(r"\b(\d{1,3})\s*(minutes?|mins?|hours?|hrs?)\b", text, re.I)
    if not match:
        return timedelta(hours=1)
    amount = int(match.group(1))
    return timedelta(hours=amount) if match.group(2).lower().startswith(("hour", "hr")) else timedelta(minutes=amount)


def _company(message: MailMessage) -> str | None:
    name, address = parseaddr(message.from_addr)
    candidate = name.strip().strip('"')
    if not candidate and address:
        candidate = address.split("@")[-1].split(".")[0]
    candidate = re.sub(r"\b(?:talent acquisition|recruiting|recruitment|careers?|notifications?)\b", "", candidate, flags=re.I)
    candidate = " ".join(candidate.replace("|", " ").replace("-", " ").split()).strip()
    return candidate[:200] or None


def _role(text: str) -> str | None:
    patterns = (
        r"\b(?:role|position|job title)\s*[:\-]\s*([^\n|,.;]{3,100})",
        r"\bfor (?:the )?([^\n|,.;]{3,100}?)\s+(?:role|position)\b",
    )
    for pattern in patterns:
        match = re.search(pattern, text, re.I)
        if match:
            return " ".join(match.group(1).split())[:240]
    return None


def _preview(text: str) -> str:
    cleaned = re.sub(r"\s+", " ", text).strip()
    cleaned = re.split(r"\b(?:unsubscribe|privacy policy)\b", cleaned, maxsplit=1, flags=re.I)[0]
    return cleaned[:800]


def extract(message: MailMessage, invite: CalendarInvite | None = None) -> ExtractedDetails:
    base = received_at(message)
    combined = f"{message.subject}\n{message.text}"
    urls = [url.rstrip(".,);]") for url in _URL.findall(message.raw_body or message.text)]
    meeting_url = next((url for url in urls if any(domain in url.lower() for domain in _MEETING_DOMAINS)), None)
    action_url = next((url for url in urls if any(domain in url.lower() for domain in _SCHEDULING_DOMAINS)), None)

    due_at = _due_datetime(combined, base)
    scheduling_request = bool(
        action_url
        and re.search(r"\b(?:choose|select|schedule|pick).{0,40}\b(?:time|slot|availability)\b", combined, re.I)
        and not re.search(r"\b(?:confirmed|scheduled for|is scheduled)\b", combined, re.I)
    )
    event_context = bool(
        re.search(
            r"\b(?:interview|appointment|meeting|call|session|scheduled|confirmed|invitation)\b",
            combined,
            re.I,
        )
    )
    event_start = invite.start if invite else (
        _find_datetime(combined, base) if event_context and not scheduling_request else None
    )
    event_end = invite.end if invite else (event_start + _duration(combined) if event_start else None)
    action_text = None
    if action_url and not event_start:
        action_text = "Choose a time"
    elif re.search(r"\b(?:please|could you|kindly)\b.{0,80}\b(?:reply|confirm|send|submit|complete|sign|select|choose|schedule)\b", combined, re.I | re.S):
        action_text = "Review and respond"
    elif due_at:
        action_text = "Complete before the deadline"

    return ExtractedDetails(
        received_at=base,
        preview=_preview(message.text or message.snippet),
        company=_company(message),
        role=_role(combined),
        action_text=action_text,
        action_url=action_url,
        due_at=due_at,
        event_start=event_start,
        event_end=event_end,
        timezone_name=(invite.timezone_name if invite else None) or ("Asia/Kolkata" if event_start else None),
        location=invite.location if invite else None,
        meeting_url=(invite.url if invite and invite.url else meeting_url),
        calendar_uid=invite.uid if invite else None,
        has_calendar_invite=invite is not None,
    )
