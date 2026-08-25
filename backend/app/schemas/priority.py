from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, Field, field_validator

from app.priority.classifier import ALLOWED_LABELS


class PriorityItemOut(BaseModel):
    id: int
    subject: str
    sender: str
    company: str | None
    role: str | None
    importance: str
    labels: list[str]
    state: str
    confidence: float
    summary: str
    why_important: str
    evidence: list[str]
    action_text: str | None
    action_url: str | None
    due_at: datetime | None
    event_start: datetime | None
    event_end: datetime | None
    timezone: str | None
    location: str | None
    meeting_url: str | None
    calendar_uid: str | None
    has_calendar_invite: bool
    gmail_url: str
    preview: str
    received_at: datetime
    snoozed_until: datetime | None
    account_label: str
    account_address: str


class PriorityHistoryOut(BaseModel):
    message_id: str
    subject: str
    sender: str
    received_at: datetime | None
    preview: str
    classification: dict


class PriorityDetailsOut(BaseModel):
    item: PriorityItemOut
    history: list[PriorityHistoryOut]


class PriorityUpdate(BaseModel):
    state: str | None = None
    labels: list[str] | None = None
    snoozed_until: datetime | None = None

    @field_validator("state")
    @classmethod
    def validate_state(cls, value: str | None) -> str | None:
        allowed = {"needs_action", "scheduled", "informational", "done", "snoozed", "cancelled"}
        if value is not None and value not in allowed:
            raise ValueError("unsupported Priority state")
        return value

    @field_validator("labels")
    @classmethod
    def validate_labels(cls, value: list[str] | None) -> list[str] | None:
        if value is None:
            return None
        normalized = list(dict.fromkeys(label.strip().casefold() for label in value if label.strip()))
        if any(label not in ALLOWED_LABELS for label in normalized):
            raise ValueError("unsupported Priority label")
        return normalized


class PriorityFeedbackIn(BaseModel):
    kind: str
    value: dict = Field(default_factory=dict)

    @field_validator("kind")
    @classmethod
    def validate_kind(cls, value: str) -> str:
        if value not in {"important", "not_important", "classification_correction"}:
            raise ValueError("unsupported feedback kind")
        return value


class PrioritySyncOut(BaseModel):
    successful: bool
    accounts_run: int
    emails_found: int
    emails_processed: int
    items_new: int
    items_updated: int
    items_review: int
    pending: int
    message: str
    account_errors: list[str]
    new_items: list[PriorityItemOut]


class MailAccountOut(BaseModel):
    id: int
    provider: str
    address: str
    display_label: str
    purposes: list[str]
    enabled: bool
    authorized: bool = False
    priority_last_success_at: datetime | None = None
    priority_last_error: str | None = None


class MailConnectionStartIn(BaseModel):
    purpose: str
    email: str | None = None
    display_label: str | None = None
    callback_port: int = Field(ge=1024, le=65535)
    force_reauthorize: bool = False

    @field_validator("purpose")
    @classmethod
    def validate_purpose(cls, value: str) -> str:
        if value not in {"finance", "priority"}:
            raise ValueError("unsupported mail purpose")
        return value

    @field_validator("email")
    @classmethod
    def validate_email(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip().casefold()
        if not normalized:
            return None
        if "@" not in normalized or len(normalized) > 320:
            raise ValueError("a valid Gmail address is required")
        return normalized


class MailConnectionStartOut(BaseModel):
    requires_authorization: bool
    account: MailAccountOut | None = None
    session_id: str | None = None
    session_secret: str | None = None
    authorization_url: str | None = None
    expires_at: datetime | None = None


class MailConnectionCompleteIn(BaseModel):
    session_id: str = Field(min_length=36, max_length=36)
    session_secret: str = Field(min_length=32, max_length=200)
    callback_url: str = Field(min_length=20, max_length=4096)
