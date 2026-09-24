"""Model-led Priority mail classification.

Two focused calls to the local Qwen model, both under server-side JSON-schema
guided decoding:

1. **Triage** (every message): is this mail about Ajmal specifically, and which
   one of five categories does it belong to (or ``none``)?
2. **Details** (only mail that passed triage): state, summary, next action,
   deadline and event times in IST.

Deterministic code only gathers facts for the prompt (headers, Gmail
categories, calendar invites, links) and validates the model's output (enums
are enforced by the decoder; dates are parsed and range-checked here). A
structured calendar invite is authoritative for event times and cancellation.
If the model is unavailable the caller defers the message; nothing is guessed.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from email.utils import getaddresses, parseaddr
from zoneinfo import ZoneInfo

from app.mail.models import MailMessage
from app.priority.calendar import CalendarInvite
from app.priority.extractor import ExtractedDetails, links, preview_text, received_at
from app.services.openclaw import InferenceWorkload, OpenClawClient

CATEGORIES = ("interview", "job", "event", "account", "personal")
ALLOWED_LABELS = set(CATEGORIES)
STATES = ("needs_action", "scheduled", "cancelled", "informational")

_IST = ZoneInfo("Asia/Kolkata")
_BODY_CHARS = 3500
_LOCAL_TIME = r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}$"
_URL = re.compile(r"https?://([^/\s<>\"']+)[^\s<>\"']*", re.I)
_GMAIL_CATEGORIES = {
    "CATEGORY_PROMOTIONS": "Promotions",
    "CATEGORY_SOCIAL": "Social",
    "CATEGORY_UPDATES": "Updates",
    "CATEGORY_FORUMS": "Forums",
    "CATEGORY_PERSONAL": "Primary",
}

# Facts for the prompt, not decisions: the model is told these senders relay
# mass recruitment mail and must still judge each message.
_JOB_BOARDS = (
    "naukri.com", "naukrigulf.com", "foundit.in", "monsterindia.com", "shine.com",
    "indeed.com", "glassdoor.com", "jobleads.com", "talent500.co", "freshersindia.in",
    "instahyre.com", "iimjobs.com", "hirist.tech", "cutshort.io", "apna.co", "wellfound.com",
)
_AUTOMATED_LOCAL = re.compile(
    r"(?:^|[._+\-])(?:no-?reply|do-?not-?reply|notifications?|alerts?|updates?|news(?:letter)?|"
    r"marketing|promotions?|offers?|deals?|mailer|info|hello|communications?)(?:$|[._+\-])",
    re.I,
)

TRIAGE_SCHEMA = {
    "type": "object",
    "properties": {
        # A short tag; a hard cap keeps a rambling model inside the token limit.
        "about": {"type": "string", "maxLength": 120},
        "mass_mail": {"type": "boolean"},
        "selling_something": {"type": "boolean"},
        "routine_notice": {"type": "boolean"},
        "his_hiring_process": {"type": "boolean"},
        "ajmal_must_act": {"type": "boolean"},
        "category": {"type": "string", "enum": [*CATEGORIES, "none"]},
        "confidence": {"type": "string", "enum": ["high", "medium", "low"]},
    },
    "required": [
        "about", "mass_mail", "selling_something", "routine_notice",
        "his_hiring_process", "ajmal_must_act", "category", "confidence",
    ],
    "additionalProperties": False,
}

_NULLABLE_TIME = {"anyOf": [{"type": "string", "pattern": _LOCAL_TIME}, {"type": "null"}]}
_NULLABLE_TEXT = {"anyOf": [{"type": "string"}, {"type": "null"}]}
DETAILS_SCHEMA = {
    "type": "object",
    "properties": {
        "state": {"type": "string", "enum": list(STATES)},
        "summary": {"type": "string"},
        "action": _NULLABLE_TEXT,
        "deadline_quote": _NULLABLE_TEXT,
        "deadline": _NULLABLE_TIME,
        "event_quote": _NULLABLE_TEXT,
        "event_start": _NULLABLE_TIME,
        "event_end": _NULLABLE_TIME,
        "company": _NULLABLE_TEXT,
        "role": _NULLABLE_TEXT,
        "location": _NULLABLE_TEXT,
    },
    "required": [
        "state", "summary", "action", "deadline_quote", "deadline", "event_quote",
        "event_start", "event_end", "company", "role", "location",
    ],
    "additionalProperties": False,
}

TRIAGE_PROMPT = """You are the mail triage step of LifeOS, Ajmal Rasi's personal assistant. Ajmal is a software / AI engineer in Bengaluru, India (timezone IST) who is actively interviewing for jobs. You see one email and decide whether it belongs on his Priority list: a short list of mail he must act on, must attend, or would be upset to miss. Most email does not belong there.

Answer each field in order, honestly and literally:
- about: what the email is, in at most 12 words.
- mass_mail: true if essentially the same email goes to many people (newsletters, promotions, job-board blasts, alerts, digests, announcements), even when it greets him by name.
- selling_something: true if it advertises or promotes a product, card, loan, offer, course, subscription, webinar, masterclass, workshop or free live class, including calendar invites sent to market a class or event.
- routine_notice: true for automatic notices that need nothing from him: receipts, successful payments, order, shipping and delivery updates, trade or order confirmations, statements or credit-score updates, OTPs, terms-of-service updates, app or social notifications, review replies. Bookings and tickets for his own upcoming travel or events are NOT routine notices.
- his_hiring_process: true only if it concerns a job application or interview process he is actually in with a named employer: an interview invitation, schedule, reschedule, cancellation or reminder, an assessment or test, a call letter, a result or offer, or a recruiter at a named company or agency writing to him about one specific role. Mail relayed by a job board or mass recruitment mailer (the From line says so) is false unless it gives him a specific interview slot, test link or offer from a named employer; "apply now", "you are a top applicant", "verify your details" and recruiter messages broadcast through the board are false.
- ajmal_must_act: true only if the email asks him specifically to do something concrete (reply, confirm, choose a slot, attend, submit, pay, verify, secure his account) and ignoring it has a real consequence.
- category, exactly one of:
  - interview: his_hiring_process with an interview, assessment, call letter, result or offer (also when sent by an applicant-tracking system or Google Calendar).
  - job: his_hiring_process where a recruiter at a named company or agency wrote to him about a specific role and expects a reply or step.
  - event: a booking or registration of his for a specific time: flights, trains, hotels, tickets, medical, bank or government appointments, meetings he is attending.
  - account: his own account, money or documents need action: a security alert about a sign-in or change on his account, a payment due or failed, a renewal or expiry, KYC, tax, legal or government notices, cloud cost overruns.
  - personal: a real person writing to him personally about something not covered above.
  - none: everything else, and anything that is selling_something, or mass_mail or a routine_notice without his_hiring_process or ajmal_must_act.
- confidence: how sure you are.

Examples:
- "Interview scheduled with LTM on Thu 4:00 PM" from admin@ripplehire.com -> interview
- "TCS_Interview Call Letter_Walkin Drive_29-Aug" from a person at tcs.com, addressed to him -> interview
- "LTIMindtree - Technical assessment" from coderpad.io -> interview
- "Kindly confirm interest level with TCS" from a named recruiter at a staffing firm -> job
- "Congratulations Ajmal! You have been eligible for L&T" or "Please submit the details urgently" from an "HR Manager" at freshersindia.in -> none (recruitment spam, mass_mail)
- "Job | Lead ML Engineer in Noida" via naukri.com, or "Job | Technology Lead" via foundit.in -> none (job-board blast)
- "Invitation: How WhatsApp Scales to Billions" from a calendar address at scaler.com -> none (selling a class)
- "Your Meta Platforms buy order is complete", "Delivered: ...", "Out for delivery", "Payment successful" -> none (routine_notice)
- "Your credit score has changed", credit card offers, "important updates" marketing from CRED -> none
- "Air India eTicket" for his upcoming flight, "Reservation reminder" from Airbnb -> event
- "Security alert: new sign-in to your Google Account", "A third-party OAuth application has been added" on GitHub -> account
- "Your Amazon Pay wallet will be marked inactive" -> account"""

DETAILS_PROMPT = """You extract structured facts from one email that is on Ajmal Rasi's Priority list (category: {category}). Today is {today}. The email was received {received}. Ajmal's timezone is IST (Asia/Kolkata, UTC+05:30).

Fields:
- state: "scheduled" = a fixed date and time he will attend (interview slot, flight, appointment). "needs_action" = he must do something (reply, choose a slot, submit, pay, verify) and nothing is fixed yet. "cancelled" = the event or interview was cancelled. "informational" = worth knowing, nothing to do.
- summary: one or two plain sentences, at most 200 characters, saying what this is and what matters (who, what, when). No greetings and no marketing language.
- action: the single next step as a short imperative of at most 8 words, e.g. "Pick an interview slot", "Reply confirming interest", "Renew the policy". null if there is nothing to do.
- deadline_quote: the exact words copied from the email that state a deadline for the action, e.g. "please respond by 28 Aug 2026". null if the email states no deadline. Do not paraphrase.
- deadline: that deadline as YYYY-MM-DDTHH:MM in IST; 23:59 if only a date is given. null whenever deadline_quote is null.
- event_quote: the exact words copied from the email or invite that state when the event happens. null if no time is stated.
- event_start, event_end: the event's start and end as YYYY-MM-DDTHH:MM in IST. Convert other timezones to IST. Resolve relative dates ("tomorrow", "this Friday") against the received date. null whenever event_quote is null; event_end is null when neither an end time nor a duration is given.
- company, role, location: short values, or null.
Only use facts stated in the email or its calendar invite. Never invent a deadline or a time.{invite_rule}"""


class PriorityModelUnavailable(RuntimeError):
    """The model could not classify this message now; retry on a later sync."""


@dataclass(frozen=True)
class PriorityClassification:
    importance: str
    labels: list[str]
    state: str
    confidence: float
    summary: str
    why_important: str
    evidence: list[str]
    details: ExtractedDetails


@dataclass(frozen=True)
class ThreadContext:
    """What LifeOS already recorded for this Gmail thread."""

    category: str | None
    state: str
    summary: str


def _ist(value: datetime) -> str:
    return value.astimezone(_IST).strftime("%a %d %b %Y, %H:%M IST")


def _compact_links(text: str) -> str:
    return _URL.sub(lambda match: f"[link: {match.group(1).lower()}]", text)


def _addressed_to(message: MailMessage, account_address: str | None) -> str:
    if not account_address:
        return "unknown"
    target = account_address.casefold()
    to = {address.casefold() for _, address in getaddresses([message.to_addrs])}
    cc = {address.casefold() for _, address in getaddresses([message.cc_addrs])}
    if target in to:
        return "in To" if len(to) <= 3 else f"in To with {len(to) - 1} other recipients"
    if target in cc:
        return "in Cc"
    return "not in To or Cc (Bcc or mailing list)"


def email_block(
    message: MailMessage,
    invite: CalendarInvite | None,
    account_address: str | None = None,
    thread: ThreadContext | None = None,
) -> str:
    """Render the facts the model sees. Pure; also used by evaluation scripts."""
    name, address = parseaddr(message.from_addr)
    headers = message.headers
    gmail = [label for key, label in _GMAIL_CATEGORIES.items() if key in message.label_ids]
    list_headers = [
        header for header in ("list-unsubscribe", "list-id") if headers.get(header)
    ]
    precedence = headers.get("precedence", "").strip()
    is_reply = bool(headers.get("in_reply_to") or headers.get("in-reply-to"))
    local, _, domain = address.casefold().partition("@")
    board = _job_board(message)
    sender_notes = []
    if board:
        sender_notes.append(f"{board} is a job board / mass recruitment mailer")
    if _AUTOMATED_LOCAL.search(local):
        sender_notes.append("automated address")
    lines = [
        f"From: {name + ' ' if name else ''}<{address}>"
        + (f" ({'; '.join(sender_notes)})" if sender_notes else ""),
        f"Ajmal is: {_addressed_to(message, account_address)}",
        f"Received: {_ist(received_at(message))}",
        f"Subject: {message.subject.strip() or '(no subject)'}",
        f"Gmail category: {', '.join(gmail) or 'none'}"
        + (" (Gmail marked it Important)" if "IMPORTANT" in message.label_ids else ""),
    ]
    if list_headers or precedence:
        lines.append(
            f"Mailing-list headers: {', '.join(list_headers) or 'none'}"
            + (f"; Precedence: {precedence}" if precedence else "")
        )
    if is_reply:
        lines.append("This is a reply within an existing conversation.")
    if message.attachments:
        names = [attachment.filename for attachment in message.attachments if attachment.filename][:6]
        if names:
            lines.append(f"Attachments: {', '.join(names)}")
    if invite:
        parts = [f"title: {invite.summary or '(none)'}"]
        if invite.start:
            parts.append(f"start: {_ist(invite.start)}")
        if invite.end:
            parts.append(f"end: {_ist(invite.end)}")
        if invite.status:
            parts.append(f"status: {invite.status}")
        if invite.organizer:
            parts.append(f"organizer: {invite.organizer}")
        if invite.location:
            parts.append(f"location: {invite.location}")
        lines.append("Calendar invite attached: " + "; ".join(parts))
    if thread:
        lines.append(
            "Earlier in this thread LifeOS recorded: "
            f"{thread.category or 'not priority'}, {thread.state}: {thread.summary[:200]}"
        )
    body = _compact_links(preview_text(message.text or message.snippet, _BODY_CHARS))
    lines.append("")
    lines.append("Body:")
    lines.append(body or "(empty)")
    return "\n".join(lines)


def _local_time(value: object, received: datetime) -> datetime | None:
    if not isinstance(value, str) or not re.fullmatch(_LOCAL_TIME, value):
        return None
    try:
        parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M").replace(tzinfo=_IST)
    except ValueError:
        return None
    # A plausible personal schedule: not years before or after the email.
    if not (received - timedelta(days=60) <= parsed <= received + timedelta(days=730)):
        return None
    return parsed.astimezone(timezone.utc)


def _text(value: object, limit: int) -> str | None:
    if not isinstance(value, str):
        return None
    cleaned = " ".join(value.split()).strip()
    if not cleaned or cleaned.casefold() in {"null", "none", "n/a", "unknown"}:
        return None
    return cleaned[:limit]


def _normalized(text: str) -> str:
    return re.sub(r"\s+", " ", text or "").casefold().strip()


def _invite_text(invite: CalendarInvite | None) -> str:
    if not invite:
        return ""
    return " ".join(
        value for value in (
            invite.summary,
            _ist(invite.start) if invite.start else None,
            _ist(invite.end) if invite.end else None,
        ) if value
    )


def _grounded(quote: object, source: str) -> bool:
    """A time is trusted only if the model quoted words that really appear
    in the email and contain a number (a date or clock time)."""
    if not isinstance(quote, str):
        return False
    cleaned = _normalized(quote).strip(" .\"'")
    return len(cleaned) >= 4 and bool(re.search(r"\d", cleaned)) and cleaned in source


def _job_board(message: MailMessage) -> str | None:
    domain = parseaddr(message.from_addr)[1].casefold().partition("@")[2]
    return next((b for b in _JOB_BOARDS if domain == b or domain.endswith("." + b)), None)


def _passes_triage(triage: dict, message: MailMessage) -> bool:
    """Apply the stated policy to the model's own answers; see TRIAGE_PROMPT."""
    if triage.get("selling_something"):
        return False
    if triage.get("category") == "job" and _job_board(message):
        # The 4B model reliably over-reads board-relayed "opportunities" as a
        # personal recruiter. A real process through a board surfaces as an
        # interview (slot, test link, call letter), which stays eligible.
        return False
    involved = bool(triage.get("his_hiring_process") or triage.get("ajmal_must_act"))
    if (triage.get("mass_mail") or triage.get("routine_notice")) and not involved:
        return False
    return True


def _triage_reason(triage: dict) -> str:
    about = _text(triage.get("about"), 300) or "Email"
    flags = [
        label
        for key, label in (
            ("his_hiring_process", "part of your hiring process"),
            ("ajmal_must_act", "asks you to act"),
            ("mass_mail", "sent to many people"),
            ("selling_something", "promotional"),
            ("routine_notice", "routine notice"),
        )
        if triage.get(key)
    ]
    return f"{about}." + (f" ({', '.join(flags)})" if flags else "")


def _suppressed(message: MailMessage, details: ExtractedDetails, reason: str, confidence: float) -> PriorityClassification:
    return PriorityClassification(
        importance="suppressed",
        labels=[],
        state="informational",
        confidence=confidence,
        summary=message.subject.strip()[:260] or "Email",
        why_important=reason[:800] or "Not about you specifically.",
        evidence=["llm_triage"],
        details=details,
    )


def classify(
    message: MailMessage,
    invite: CalendarInvite | None = None,
    *,
    client: OpenClawClient | None = None,
    account_address: str | None = None,
    thread: ThreadContext | None = None,
    now: datetime | None = None,
) -> PriorityClassification:
    """Classify one message. Raises ``PriorityModelUnavailable`` to defer."""
    from app.services.openclaw import OpenClawUnavailable

    client = client or OpenClawClient(workload=InferenceWorkload.BACKGROUND, admission_wait=60.0)
    received = received_at(message)
    block = email_block(message, invite, account_address, thread)
    found = links(message)
    base = ExtractedDetails(
        received_at=received,
        preview=preview_text(message.text or message.snippet, 800),
        company=None,
        role=None,
        action_text=None,
        action_url=found.scheduling_url,
        due_at=None,
        event_start=invite.start if invite else None,
        event_end=invite.end if invite else None,
        timezone_name=(invite.timezone_name if invite else None),
        location=invite.location if invite else None,
        meeting_url=(invite.url if invite and invite.url else found.meeting_url),
        calendar_uid=invite.uid if invite else None,
        has_calendar_invite=invite is not None,
    )

    try:
        triage = client.chat_json(
            [
                {"role": "system", "content": TRIAGE_PROMPT},
                {"role": "user", "content": block},
            ],
            schema=TRIAGE_SCHEMA,
            schema_name="priority_triage",
            max_tokens=384,
        )
    except OpenClawUnavailable as exc:
        raise PriorityModelUnavailable(f"Triage deferred: {exc}") from exc

    category = triage.get("category")
    confidence = {"high": 0.9, "medium": 0.7, "low": 0.5}.get(str(triage.get("confidence")), 0.5)
    reason = _triage_reason(triage)
    if category not in CATEGORIES or not _passes_triage(triage, message):
        return _suppressed(message, base, reason, confidence)

    today = (now or datetime.now(timezone.utc)).astimezone(_IST).strftime("%A %d %B %Y")
    invite_rule = (
        "\nThe calendar invite is authoritative for the event time; copy its start and end."
        if invite and invite.start
        else ""
    )
    try:
        data = client.chat_json(
            [
                {
                    "role": "system",
                    "content": DETAILS_PROMPT.format(
                        category=category,
                        today=today,
                        received=_ist(received),
                        invite_rule=invite_rule,
                    ),
                },
                {"role": "user", "content": block},
            ],
            schema=DETAILS_SCHEMA,
            schema_name="priority_details",
            max_tokens=640,
        )
    except OpenClawUnavailable as exc:
        raise PriorityModelUnavailable(f"Details deferred: {exc}") from exc

    evidence = ["llm_triage", "llm_details"]
    state = data.get("state") if data.get("state") in STATES else "informational"
    source = _normalized(" ".join([message.subject, message.text or message.snippet, _invite_text(invite)]))
    event_start = event_end = due_at = None
    if _grounded(data.get("event_quote"), source):
        event_start = _local_time(data.get("event_start"), received)
        event_end = _local_time(data.get("event_end"), received)
        if event_start:
            evidence.append("event_time_quoted")
    if _grounded(data.get("deadline_quote"), source):
        due_at = _local_time(data.get("deadline"), received)
        if due_at:
            evidence.append("deadline_quoted")
    if invite:
        evidence.append("calendar_invite")
        if invite.start:
            event_start, event_end = invite.start, invite.end
        if invite.status == "cancelled":
            state = "cancelled"
    if event_start and event_end and event_end <= event_start:
        event_end = None
    if due_at and event_start and due_at == event_start:
        due_at = None  # The event itself, not a separate deadline.
    if state == "scheduled" and event_start is None:
        # A schedule without a parseable time cannot be shown or calendared.
        state = "needs_action" if _text(data.get("action"), 120) else "informational"
    action = _text(data.get("action"), 120) if state == "needs_action" else None
    if found.scheduling_url and state == "needs_action" and not action:
        action = "Choose a time"

    details = ExtractedDetails(
        received_at=received,
        preview=base.preview,
        company=_text(data.get("company"), 200),
        role=_text(data.get("role"), 240),
        action_text=action,
        action_url=found.scheduling_url if state == "needs_action" else None,
        due_at=due_at,
        event_start=event_start,
        event_end=event_end,
        timezone_name=base.timezone_name or ("Asia/Kolkata" if event_start else None),
        location=_text(data.get("location"), 500) or base.location,
        meeting_url=base.meeting_url,
        calendar_uid=base.calendar_uid,
        has_calendar_invite=base.has_calendar_invite,
    )
    importance = (
        "high"
        if state in {"needs_action", "scheduled"} or (category == "interview" and state != "cancelled")
        else "normal"
    )
    summary = _text(data.get("summary"), 260) or message.subject.strip()[:260] or "Important email"
    return PriorityClassification(
        importance=importance,
        labels=[category],
        state=state,
        confidence=confidence,
        summary=summary,
        why_important=reason or "The model marked this as about you specifically.",
        evidence=evidence,
        details=details,
    )
