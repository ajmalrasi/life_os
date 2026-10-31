"""Read-only Gmail provider backed by the gog Google Workspace CLI."""

from __future__ import annotations

import html
import logging
import re

from app.mail.gog import GogCommandError, GogRunner
from app.mail.models import MailAttachment, MailMessage

logger = logging.getLogger(__name__)

class MailProviderError(RuntimeError):
    """A mailbox read failed and must not be reported as a successful sync."""


class MailRateLimitError(MailProviderError):
    """A temporary Gmail quota limit; a paced import may retry this read."""


class GmailProvider:
    def __init__(
        self,
        account: str,
        keyring_password: str = "",
        gog_bin: str = "gog",
        home: str | None = None,
    ) -> None:
        self.account = account.strip()
        self.keyring_password = keyring_password
        self.gog_bin = gog_bin
        self.home = home
        self.runner = GogRunner(
            keyring_password=keyring_password,
            gog_bin=gog_bin,
            home=home,
        )

    def is_available(self) -> bool:
        return bool(self.account) and self.runner.available

    def _run(self, args: list[str]) -> str:
        try:
            return self.runner.run(args, account=self.account)
        except GogCommandError as exc:
            lowered = exc.stderr.casefold()
            if "ratelimitexceeded" in lowered or "quota exceeded" in lowered:
                raise MailRateLimitError("Gmail temporarily limited reads; retry after a pause.") from exc
            if "invalid_grant" in lowered or "insufficientpermissions" in lowered:
                raise MailProviderError(
                    f"Gmail authorization expired for {self.account} or lacks read access. "
                    "Reauthorize that LifeOS mail connection."
                ) from exc
            if "too long" in str(exc).casefold():
                raise MailProviderError(
                    f"Gmail account {self.account} took too long to respond."
                ) from exc
            raise MailProviderError(f"Gmail account {self.account} could not be read.") from exc

    @staticmethod
    def _json(out: str, operation: str) -> dict:
        try:
            import json

            data = json.loads(out)
        except json.JSONDecodeError as exc:
            logger.warning("gog returned invalid JSON while %s", operation)
            raise MailProviderError("Gmail returned an invalid response. Try the sync again.") from exc
        if not isinstance(data, dict):
            raise MailProviderError("Gmail returned an invalid response. Try the sync again.")
        return data

    def search(self, query: str, max_results: int = 25) -> list[dict]:
        out = self._run(["gmail", "messages", "list", query, "--max", str(max_results), "-j"])
        if not out:
            return []
        return self._json(out, "listing messages").get("messages", [])

    def search_page(
        self, query: str, *, page_token: str | None = None, page_size: int = 100
    ) -> tuple[list[dict], str | None]:
        """Read one explicit page for audited imports; normal sync stays capped.

        A missing/malformed response is not evidence of an empty mailbox. The
        caller owns pagination and durable checkpoints, so it can resume the
        exact query rather than moving an incremental sync cursor.
        """
        if not 1 <= page_size <= 500:
            raise ValueError("page_size must be between 1 and 500")
        args = ["gmail", "messages", "list", query, "--max", str(page_size),
                "--timezone", "Asia/Kolkata", "-j"]
        if page_token:
            args.extend(["--page", page_token])
        out = self._run(args)
        if not out:
            raise MailProviderError("Gmail returned an empty search response.")
        data = self._json(out, "listing an inventory page")
        rows = data.get("messages")
        token = data.get("nextPageToken")
        if (not isinstance(rows, list)
                or any(not isinstance(row, dict) or not isinstance(row.get("id"), str)
                       or not row["id"] for row in rows)
                or (token is not None and not isinstance(token, str))):
            raise MailProviderError("Gmail returned an invalid inventory page.")
        return rows, token or None

    def get_message(self, message_id: str) -> MailMessage | None:
        out = self._run(["gmail", "get", message_id, "-j"])
        if not out:
            raise MailProviderError("Gmail returned an empty message. Try the sync again.")
        data = self._json(out, "reading a message")
        raw_headers = data.get("headers") or {}
        headers = {str(key).lower(): str(value) for key, value in raw_headers.items()}
        raw = data.get("body") or ""
        message = data.get("message") or {}
        try:
            internal_ms = int(message.get("internalDate") or data.get("internalDate") or 0)
        except (TypeError, ValueError):
            internal_ms = 0
        label_ids = message.get("labelIds") or data.get("labelIds") or []
        attachments = tuple(
            MailAttachment(
                attachment_id=str(item.get("attachmentId") or ""),
                filename=str(item.get("filename") or ""),
                mime_type=str(item.get("mimeType") or ""),
            )
            for item in (data.get("attachments") or [])
            if item.get("attachmentId")
        )
        return MailMessage(
            message_id=message_id,
            thread_id=message.get("threadId") or data.get("threadId"),
            subject=headers.get("subject", ""),
            from_addr=headers.get("from", ""),
            to_addrs=headers.get("to", ""),
            cc_addrs=headers.get("cc", ""),
            received=headers.get("date", ""),
            text=strip_html(raw),
            raw_body=raw,
            internal_date_ms=internal_ms,
            headers=headers,
            label_ids=tuple(str(value) for value in label_ids),
            snippet=str(data.get("snippet") or message.get("snippet") or ""),
            attachments=attachments,
        )

    def list_attachments(self, message_id: str) -> list[dict]:
        out = self._run(["gmail", "get", message_id, "-j"])
        if not out:
            return []
        data = self._json(out, "listing attachments")
        return [
            {
                "filename": str(item.get("filename") or ""),
                "attachmentId": str(item.get("attachmentId") or ""),
                "mimeType": str(item.get("mimeType") or ""),
            }
            for item in (data.get("attachments") or [])
            if item.get("attachmentId")
        ]

    def list_pdf_attachments(self, message_id: str) -> list[dict]:
        return [
            item for item in self.list_attachments(message_id)
            if item["filename"].lower().endswith(".pdf")
        ]

    def download_attachment(self, message_id: str, attachment_id: str, out_path: str) -> bool:
        self._run(["gmail", "attachment", message_id, attachment_id, "--out", out_path])
        return True


def strip_html(body: str) -> str:
    t = re.sub(r"<(script|style)[^>]*>.*?</\1>", " ", body, flags=re.S | re.I)
    t = re.sub(r"<[^>]+>", " ", t)
    t = html.unescape(t)
    t = re.sub(r"[ \t]+", " ", t)
    t = re.sub(r"\s*\n\s*", "\n", t)
    return t.strip()
