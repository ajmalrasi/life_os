from __future__ import annotations

import re
from dataclasses import dataclass

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
_RECRUITER = re.compile(r"\b(?:recruiter|recruiting|talent acquisition|hiring team|opportunity)\b", re.I)
_APPOINTMENT = re.compile(r"\b(?:appointment|consultation|reservation|meeting confirmed)\b", re.I)
_SECURITY = re.compile(r"\b(?:security alert|unusual sign[ -]?in|password reset|verify your identity|account locked)\b", re.I)
_TRAVEL = re.compile(r"\b(?:flight|boarding|hotel|booking confirmed|itinerary|check[ -]?in)\b", re.I)
_DOCUMENT = re.compile(r"\b(?:document|signature|sign(?:ed|ing)?|attachment|required form|offer letter)\b", re.I)
_ACTION = re.compile(r"\b(?:please|could you|kindly|action required|respond|reply|confirm|choose|select|schedule|submit|complete|send|sign)\b", re.I)
_CANCEL = re.compile(r"\b(?:cancelled|canceled|will not take place|interview cancellation)\b", re.I)
_RESCHEDULE = re.compile(r"\b(?:rescheduled|new time|time has changed|updated invitation)\b", re.I)
_BULK = re.compile(
    r"\b(?:jobs? (?:alert|digest)|recommended jobs?|jobs? for you|weekly digest|newsletter|top picks|new openings)\b",
    re.I,
)
_APPLICATION_RECEIPT = re.compile(
    r"\b(?:application (?:has been )?received|thanks? for applying|application confirmation|we received your application)\b",
    re.I,
)
_EDUCATIONAL = re.compile(r"\b(?:interview tips|prepare for interviews?|interview questions?|webinar)\b", re.I)


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
    sender = message.from_addr.casefold()
    headers = message.headers
    if any(token in sender for token in ("no-reply", "noreply", "donotreply", "notifications@", "mailer-daemon")):
        return False
    if headers.get("list-unsubscribe") or headers.get("list-id"):
        return False
    precedence = headers.get("precedence", "").casefold()
    if precedence in {"bulk", "list", "junk"}:
        return False
    return bool(message.to_addrs or re.search(r"\b(?:hi|hello|dear)\b", message.text[:300], re.I))


def classify(message: MailMessage, invite: CalendarInvite | None = None) -> PriorityClassification:
    details = extract(message, invite)
    text = f"{message.subject}\n{message.text}"
    evidence: list[str] = []
    labels: list[str] = []

    interview = bool(_INTERVIEW.search(text))
    recruiter = bool(_RECRUITER.search(text))
    direct = _direct_human(message)
    calendar = invite is not None

    if interview:
        labels.extend(("work", "interview"))
        evidence.append("interview_language")
    if recruiter:
        labels.extend(("work", "recruiter"))
        evidence.append("recruiting_sender_or_language")
    if _APPOINTMENT.search(text):
        labels.append("appointment")
        evidence.append("appointment_language")
    if _SECURITY.search(text):
        labels.append("security")
        evidence.append("security_action_language")
    if _TRAVEL.search(text):
        labels.append("travel")
        evidence.append("travel_confirmation_language")
    if _DOCUMENT.search(text):
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

    strong_priority = bool(labels) or calendar or details.action_text is not None
    bulk_noise = bool(_BULK.search(text) or _APPLICATION_RECEIPT.search(text) or _EDUCATIONAL.search(text))
    if bulk_noise and not (calendar or (interview and _ACTION.search(text))):
        return PriorityClassification(
            importance="suppressed",
            labels=[],
            state="informational",
            confidence=0.96,
            summary=message.subject or "Bulk mail",
            why_important="Bulk notification without a personal action.",
            evidence=["bulk_or_application_receipt"],
            details=details,
        )
    if not strong_priority and (_ACTION.search(text) or re.search(r"\b(?:invitation|request|important update)\b", text, re.I)):
        return PriorityClassification(
            importance="review",
            labels=[],
            state="needs_action" if _ACTION.search(text) else "informational",
            confidence=0.45,
            summary=details.preview[:260] or message.subject or "Message to review",
            why_important="This may contain a personal request, but the signals are ambiguous.",
            evidence=["ambiguous_priority_signal"],
            details=details,
        )
    if not strong_priority:
        return PriorityClassification(
            importance="suppressed",
            labels=[],
            state="informational",
            confidence=0.82,
            summary=message.subject or "Email",
            why_important="No direct action, schedule, or personal-priority signal was found.",
            evidence=["no_priority_signal"],
            details=details,
        )

    unique_labels = [label for label in dict.fromkeys(labels) if label in ALLOWED_LABELS]
    if _CANCEL.search(text) or (invite and invite.status == "cancelled"):
        state = "cancelled"
        evidence.append("cancellation_language")
    elif details.event_start:
        state = "scheduled"
        evidence.append("event_time_extracted")
        if _RESCHEDULE.search(text):
            evidence.append("reschedule_language")
    elif details.action_text or _ACTION.search(text):
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
