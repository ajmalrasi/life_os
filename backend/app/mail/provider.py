from __future__ import annotations

from typing import Protocol, runtime_checkable

from app.mail.models import MailMessage


@runtime_checkable
class MailProvider(Protocol):
    account: str

    def is_available(self) -> bool: ...

    def search(self, query: str, max_results: int = 25) -> list[dict]: ...

    def get_message(self, message_id: str) -> MailMessage | None: ...

    def list_attachments(self, message_id: str) -> list[dict]: ...

    def download_attachment(self, message_id: str, attachment_id: str, out_path: str) -> bool: ...
