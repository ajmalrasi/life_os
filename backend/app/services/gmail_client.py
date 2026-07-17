"""Sync, read-only Gmail access via the ``gog`` CLI (Google Workspace).

Mirrors ``github_mcp_client.py``: the ``gog`` binary is baked into the API
image and auth is injected via environment — here a shared ``gogcli`` config
directory (OAuth client + refresh token) plus the file-keyring password. Only
search / get are used, and the stored OAuth token is read-only, so this cannot
mutate the mailbox.

We shell out to ``gog ... -j`` and parse JSON rather than embedding a client,
because gog is a standalone tool shared with other consumers on the host.
"""

from __future__ import annotations

import html
import json
import logging
import os
import re
import shutil
import subprocess

from app.services.expense_parsers.base import BankEmail

logger = logging.getLogger(__name__)

_TIMEOUT = 30.0


class GmailClientError(RuntimeError):
    """A Gmail read failed and the sync must not be reported as successful."""


class GmailClient:
    def __init__(
        self,
        account: str,
        keyring_password: str = "",
        gog_bin: str = "gog",
        home: str | None = None,
    ) -> None:
        self.account = account
        self.keyring_password = keyring_password
        self.gog_bin = gog_bin
        self.home = home

    def is_available(self) -> bool:
        """True if an account is configured and the gog binary is on PATH."""
        return bool(self.account) and shutil.which(self.gog_bin) is not None

    # -- internals ---------------------------------------------------------------

    def _env(self) -> dict[str, str]:
        env = {**os.environ}
        if self.keyring_password:
            env["GOG_KEYRING_PASSWORD"] = self.keyring_password
        if self.home:
            env["GOG_HOME"] = self.home
        return env

    def _run(self, args: list[str]) -> str:
        try:
            proc = subprocess.run(
                [self.gog_bin, "-a", self.account, *args],
                capture_output=True,
                text=True,
                timeout=_TIMEOUT,
                env=self._env(),
            )
        except subprocess.TimeoutExpired as exc:
            logger.warning("gog call failed: %s", exc)
            raise GmailClientError("Gmail took too long to respond. Try the sync again.") from exc
        except (FileNotFoundError, OSError) as exc:
            logger.warning("gog call failed: %s", exc)
            raise GmailClientError("The Gmail reader is unavailable on the LifeOS server.") from exc
        if proc.returncode != 0:
            stderr = proc.stderr.strip()
            logger.warning("gog error (%s): %s", proc.returncode, stderr[:200])
            lowered = stderr.lower()
            if "invalid_grant" in lowered or "insufficientpermissions" in lowered:
                raise GmailClientError(
                    "Gmail authorization expired or lacks read access. "
                    "Reauthorize the LifeOS Gmail connection."
                )
            raise GmailClientError("Gmail could not be read. Check the LifeOS server logs.")
        return proc.stdout

    @staticmethod
    def _json(out: str, operation: str) -> dict:
        try:
            data = json.loads(out)
        except json.JSONDecodeError as exc:
            logger.warning("gog returned invalid JSON while %s", operation)
            raise GmailClientError("Gmail returned an invalid response. Try the sync again.") from exc
        if not isinstance(data, dict):
            raise GmailClientError("Gmail returned an invalid response. Try the sync again.")
        return data

    # -- public API --------------------------------------------------------------

    def search(self, query: str, max_results: int = 25) -> list[dict]:
        """Return message metadata dicts (id, subject, from, date) for a query."""
        out = self._run(["gmail", "messages", "list", query, "--max", str(max_results), "-j"])
        if not out:
            return []
        data = self._json(out, "listing messages")
        return data.get("messages", [])

    def get_message(self, message_id: str) -> BankEmail | None:
        """Fetch one message and return it with the body stripped to text."""
        out = self._run(["gmail", "get", message_id, "-j"])
        if not out:
            raise GmailClientError("Gmail returned an empty message. Try the sync again.")
        data = self._json(out, "reading a message")
        headers = data.get("headers") or {}
        raw = data.get("body") or ""
        message = data.get("message") or {}
        try:
            internal_ms = int(message.get("internalDate") or 0)
        except (TypeError, ValueError):
            internal_ms = 0
        return BankEmail(
            message_id=message_id,
            subject=headers.get("subject", ""),
            from_addr=headers.get("from", ""),
            received=headers.get("date", ""),
            text=strip_html(raw),
            raw_body=raw,
            internal_date_ms=internal_ms,
        )


    def list_pdf_attachments(self, message_id: str) -> list[dict]:
        """Return [{'filename', 'attachmentId'}] for PDF attachments of a message."""
        out = self._run(["gmail", "get", message_id, "-j"])
        if not out:
            return []
        data = self._json(out, "listing attachments")
        return [
            {"filename": a.get("filename", ""), "attachmentId": a.get("attachmentId", "")}
            for a in (data.get("attachments") or [])
            if (a.get("filename") or "").lower().endswith(".pdf") and a.get("attachmentId")
        ]

    def download_attachment(self, message_id: str, attachment_id: str, out_path: str) -> bool:
        out = self._run(
            ["gmail", "attachment", message_id, attachment_id, "--out", out_path]
        )
        return True


def strip_html(body: str) -> str:
    """Reduce an HTML email body to readable, whitespace-collapsed text."""
    t = re.sub(r"<(script|style)[^>]*>.*?</\1>", " ", body, flags=re.S | re.I)
    t = re.sub(r"<[^>]+>", " ", t)
    t = html.unescape(t)
    t = re.sub(r"[ \t]+", " ", t)
    t = re.sub(r"\s*\n\s*", "\n", t)
    return t.strip()
