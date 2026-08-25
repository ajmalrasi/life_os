from __future__ import annotations

import json

from app.mail.models import MailMessage
from app.priority.classifier import ALLOWED_LABELS, PriorityClassification
from app.services.openclaw import OpenClawClient, OpenClawUnavailable

_SCHEMA = {
    "type": "object",
    "properties": {
        "important": {"type": "boolean"},
        "labels": {"type": "string"},
        "state": {"type": "string", "enum": ["needs_action", "scheduled", "informational"]},
        "summary": {"type": "string"},
        "why": {"type": "string"},
    },
    "required": ["important", "labels", "state", "summary", "why"],
    "additionalProperties": False,
}


def review_ambiguous(
    message: MailMessage,
    base: PriorityClassification,
    client: OpenClawClient | None = None,
) -> PriorityClassification:
    """Optional semantic review. Failure preserves Review rather than suppressing mail."""
    messages = [
        {
            "role": "system",
            "content": (
                "Classify whether this email personally deserves the user's attention. "
                "Bulk newsletters, generic job alerts, ads, and application receipts are not important. "
                "Direct human requests, deadlines, appointments, security actions, and interviews are. "
                "Use only these comma-separated labels: " + ", ".join(sorted(ALLOWED_LABELS)) + "."
            ),
        },
        {"role": "user", "content": f"SUBJECT: {message.subject}\nFROM: {message.from_addr}\nBODY:\n{message.text[:3000]}"},
    ]
    try:
        raw = (client or OpenClawClient()).chat_structured(
            messages, schema=_SCHEMA, schema_name="priority_mail", temperature=0.0
        )
        data = json.loads(raw)
    except (OpenClawUnavailable, ValueError, TypeError):
        return base
    labels = [
        token.strip().casefold()
        for token in str(data.get("labels", "")).split(",")
        if token.strip().casefold() in ALLOWED_LABELS
    ]
    if not data.get("important"):
        return base
    return PriorityClassification(
        importance="normal",
        labels=list(dict.fromkeys(labels)),
        state=str(data.get("state")),
        confidence=0.65,
        summary=str(data.get("summary") or base.summary)[:800],
        why_important=str(data.get("why") or "Semantic review marked this as personally relevant.")[:800],
        evidence=base.evidence + ["local_model_review"],
        details=base.details,
    )
