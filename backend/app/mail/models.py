from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class MailAttachment:
    attachment_id: str
    filename: str = ""
    mime_type: str = ""


@dataclass(frozen=True)
class MailMessage:
    """A provider-neutral message with only the fields consumers may inspect."""

    message_id: str
    subject: str
    from_addr: str
    received: str
    text: str
    raw_body: str
    internal_date_ms: int = 0
    thread_id: str | None = None
    to_addrs: str = ""
    cc_addrs: str = ""
    headers: dict[str, str] = field(default_factory=dict)
    label_ids: tuple[str, ...] = ()
    snippet: str = ""
    attachments: tuple[MailAttachment, ...] = ()
