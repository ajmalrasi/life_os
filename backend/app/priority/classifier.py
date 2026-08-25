from __future__ import annotations

import re
from dataclasses import dataclass
from email.utils import parseaddr

from app.mail.models import MailMessage
from app.priority.calendar import CalendarInvite
from app.priority.extractor import ExtractedDetails, extract

ALLOWED_LABELS = {
    "interview", "recruiter", "personal", "work", "appointment", "deadline",
    "security", "travel", "document",
}

_INTERVIEW = re.compile(
    r"\b(?:interview|screening call|technical round|hiring manager|recruiter call|phone screen)\b",
    re.I,
)
_RECRUITER = re.compile(r"\b(?:recruiter|recruiting|talent acquisition|hiring team)\b", re.I)
_APPOINTMENT = re.compile(r"\b(?:appointment|consultation|meeting confirmed)\b", re.I)
_SECURITY = re.compile(r"\b(?:security alert|unusual sign[ -]?in|password reset|verify your identity|account locked)\b", re.I)
_TRAVEL = re.compile(r"\b(?:booking|reservation|flight) (?:is )?confirmed\b|\bitinerary\b|\bboarding pass\b|\bcheck[ -]?in (?:opens?|reminder)\b", re.I)
_DOCUMENT = re.compile(r"\b(?:documents? (?:needed|required)|signature required|required form|offer letter|please (?:send|sign|submit).{0,40}(?:document|form))\b", re.I)
_ACTION = re.compile(r"\b(?:please|could you|kindly|action required|respond|reply|confirm|choose|select|schedule|submit|complete|send|sign)\b", re.I)
_CANCEL = re.compile(r"\b(?:cancelled|canceled|will not take place|interview cancellation)\b", re.I)
_RESCHEDULE = re.compile(r"\b(?:rescheduled|new time|time has changed|updated invitation)\b", re.I)
_BULK = re.compile(
    r"\b(?:jobs? (?:alert|digest)|recommended jobs?|jobs? for you|weekly (?:digest|report)|newsletter|"
    r"top picks|new openings|top employers|recruiters are searching|roles similar to yours|"
    r"price drops?|flash sale|mega sale|special offers?|coupon|discount|unlock interviews|walk[ -]?in drive)\b",
    re.I,
)
_APPLICATION_RECEIPT = re.compile(
    r"\b(?:application (?:has been )?received|thanks? for applying|application confirmation|we received your application)\b",
    re.I,
)
_EDUCATIONAL = re.compile(r"\b(?:interview tips|prepare for interviews?|interview questions?|webinar)\b", re.I)
_PROMOTIONAL = re.compile(
    r"\b(?:sale (?:is )?(?:live|starts|ends)|up to \d+% off|shop now|book now|buy now|"
    r"limited[ -]?time (?:deal|offer)|recommended for you|selected for you|free shipping|"
    r"new tutorial|streaming this week|unsubscribe)\b",
    re.I,
)
_ROUTINE_TRANSACTION = re.compile(
    r"\b(?:order (?:has been )?(?:confirmed|delivered|shipped)|payment successful|"
    r"rate your (?:delivery|experience)|replied to your review|weekly (?:stats|progress report))\b",
    re.I,
)
_CONFIRMED_INTERVIEW = re.compile(
    r"\b(?:your .{0,30}interview|interview (?:is |has been )?(?:scheduled|confirmed|rescheduled|cancelled|canceled)|"
    r"interview (?:invitation|details)|online interview|technical interview|screening (?:call|interview))\b",
    re.I,
)
_DIRECT_REQUEST = re.compile(
    r"\b(?:please|could you|kindly|action required)\b.{0,100}\b(?:reply|confirm|send|submit|complete|sign|select|choose|schedule)\b",
    re.I | re.S,
)
_ACCOUNT_CONSEQUENCE = re.compile(
    r"\b(?:account|wallet|card|policy|subscription).{0,80}\b(?:inactive|suspended|locked|expire|expires|overdue|closed)\b",
    re.I | re.S,
)
_GENERIC_SENDER = re.compile(
    r"(?:^|[._+\-])(?:no-?reply|do-?not-?reply|notifications?|alerts?|updates?|news(?:letter)?|"
    r"marketing|promotions?|offers?|deals?|support|info|hello|shop|payments?|careers?|"
    r"opportunities|mailer|contactus)(?:$|[._+\-])",
    re.I,
)
_BULK_DOMAINS = (
    "naukri.com", "naukrigulf.com", "foundit.in", "monsterindia.com", "shine.com",
    "jobleads.com", "flipboard.com", "stackcommerce.com", "udemymail.com",
)


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


def _direct_human(message: MailMessage) -> bool:
    name, address = parseaddr(message.from_addr)
    sender = address.casefold()
    headers = message.headers
    local, _, domain = sender.partition("@")
    if not local or not domain or _GENERIC_SENDER.search(local):
        return False
    if any(domain == blocked or domain.endswith(f".{blocked}") for blocked in _BULK_DOMAINS):
        return False
    if headers.get("list-unsubscribe") or headers.get("list-id"):
        return False
    precedence = headers.get("precedence", "").casefold()
    if precedence in {"bulk", "list", "junk"}:
        return False
    generic_name = re.search(r"\b(?:team|support|newsletter|alerts?|notifications?|careers?)\b", name, re.I)
    salutation = re.search(r"\b(?:hi|hello|dear)\s+(?:ajmal\b|mr\.?\s+ajmal\b)", message.text[:400], re.I)
    personal_address = bool(re.fullmatch(r"[a-z][a-z0-9]*(?:[._-][a-z][a-z0-9]*){0,3}", local, re.I))
    return bool(message.to_addrs and not generic_name and (salutation or personal_address))


def _automated_or_bulk_sender(message: MailMessage) -> bool:
    _, address = parseaddr(message.from_addr)
    local, _, domain = address.casefold().partition("@")
    return bool(
        _GENERIC_SENDER.search(local)
        or any(domain == blocked or domain.endswith(f".{blocked}") for blocked in _BULK_DOMAINS)
    )


def classify(message: MailMessage, invite: CalendarInvite | None = None) -> PriorityClassification:
    details = extract(message, invite)
    text = f"{message.subject}\n{message.text}"
    evidence: list[str] = []
    labels: list[str] = []

    interview = bool(_INTERVIEW.search(text))
    recruiter = bool(_RECRUITER.search(text))
    direct = _direct_human(message)
    calendar = invite is not None
    security = bool(_SECURITY.search(text))
    travel = bool(_TRAVEL.search(text))
    appointment = bool(_APPOINTMENT.search(text))
    document = bool(_DOCUMENT.search(text))
    direct_request = bool(direct and _DIRECT_REQUEST.search(text))
    confirmed_interview = bool(interview and (_CONFIRMED_INTERVIEW.search(text) or details.event_start))
    consequence_deadline = bool(details.due_at and _ACCOUNT_CONSEQUENCE.search(text))
    bulk_noise = bool(
        _BULK.search(text)
        or _APPLICATION_RECEIPT.search(text)
        or _EDUCATIONAL.search(text)
        or _PROMOTIONAL.search(text)
        or _ROUTINE_TRANSACTION.search(text)
        or _automated_or_bulk_sender(message)
    )
    protected_priority = bool(
        calendar or security or confirmed_interview or travel or consequence_deadline
        or (appointment and details.event_start)
    )

    if bulk_noise and not protected_priority:
        return PriorityClassification(
            importance="suppressed",
            labels=[],
            state="informational",
            confidence=0.96,
            summary=message.subject or "Bulk mail",
            why_important="Automated, promotional, or routine mail without a personal action.",
            evidence=["bulk_or_routine_mail"],
            details=details,
        )

    strong_priority = bool(
        protected_priority
        or direct_request
        or (document and (direct or details.due_at))
        or (interview and direct and not _EDUCATIONAL.search(text))
        or (recruiter and direct and _ACTION.search(text))
    )
    if not strong_priority:
        return PriorityClassification(
            importance="suppressed",
            labels=[],
            state="informational",
            confidence=0.9,
            summary=message.subject or "Email",
            why_important="No concrete personal action, confirmed schedule, or high-priority signal was found.",
            evidence=["no_priority_signal"],
            details=details,
        )

    if interview:
        labels.extend(("work", "interview"))
        evidence.append("interview_language")
    if recruiter:
        labels.extend(("work", "recruiter"))
        evidence.append("recruiting_sender_or_language")
    if appointment:
        labels.append("appointment")
        evidence.append("appointment_language")
    if security:
        labels.append("security")
        evidence.append("security_action_language")
    if travel:
        labels.append("travel")
        evidence.append("travel_confirmation_language")
    if document:
        labels.append("document")
        evidence.append("document_language")
    if details.due_at:
        labels.append("deadline")
        evidence.append("explicit_deadline")
    if calendar:
        evidence.append("structured_calendar_invite")
    if direct:
        evidence.append("direct_human_message")
        if not ({"work", "interview", "recruiter"} & set(labels)):
            labels.append("personal")

    unique_labels = [label for label in dict.fromkeys(labels) if label in ALLOWED_LABELS]
    if _CANCEL.search(text) or (invite and invite.status == "cancelled"):
        state = "cancelled"
        evidence.append("cancellation_language")
    elif details.event_start:
        state = "scheduled"
        evidence.append("event_time_extracted")
        if _RESCHEDULE.search(text):
            evidence.append("reschedule_language")
    elif details.action_text or direct_request or (interview and not details.event_start):
        state = "needs_action"
        evidence.append("explicit_action_request")
    else:
        state = "informational"

    importance = "high" if ({"interview", "security", "deadline"} & set(unique_labels) or state in {"needs_action", "scheduled"}) else "normal"
    confidence = 0.96 if calendar else 0.9 if interview or details.due_at else 0.76 if direct else 0.62
    subject = message.subject.strip() or "Important message"
    if "interview" in unique_labels and state == "scheduled":
        summary = f"Interview scheduled{f' for the {details.role} role' if details.role else ''}."
    elif "interview" in unique_labels and state == "needs_action":
        summary = f"Interview invitation requires an action{f' for the {details.role} role' if details.role else ''}."
    else:
        summary = details.preview[:260] or subject
    why = (
        "This message contains a confirmed schedule."
        if state == "scheduled"
        else "This message asks you to take a specific action."
        if state == "needs_action"
        else "This appears to be a direct, personally relevant message."
    )
    return PriorityClassification(
        importance=importance,
        labels=unique_labels,
        state=state,
        confidence=confidence,
        summary=summary,
        why_important=why,
        evidence=list(dict.fromkeys(evidence)),
        details=details,
    )
