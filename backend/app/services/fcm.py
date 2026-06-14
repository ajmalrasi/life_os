"""Firebase Cloud Messaging (HTTP v1) sender — Phase 1 #5 skeleton.

The backend already generates notification objects; this delivers the
high-priority ones to registered devices so the phone learns about burnout
warnings / peak-state alerts without waiting for the next manual sync.

Wiring is intentionally lazy and fail-safe:

* If ``FIREBASE_CREDENTIALS_PATH`` is unset, every call is a no-op.
* The ``google-auth`` dependency is imported lazily, so the backend runs fine
  without it installed — you only need it once you actually turn push on. Add
  ``google-auth`` to requirements.txt and drop your service-account JSON in
  place, and pushes start flowing with no other code changes.

This module never raises into the request path: delivery is best-effort and a
failure to push must not break recompute or the notifications endpoint.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass

import httpx

from app.config import Settings, get_settings

logger = logging.getLogger(__name__)

_FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
_FCM_ENDPOINT = "https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"
# High-priority severities we actually push; "info" stays poll-only.
_PUSH_SEVERITIES = {"warning", "positive"}


@dataclass
class PushResult:
    attempted: int
    delivered: int
    enabled: bool
    detail: str


class FcmService:
    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()

    @property
    def enabled(self) -> bool:
        return self.settings.fcm_enabled

    def push_notifications(self, notifications, tokens: list[str]) -> PushResult:
        """Push the high-priority notifications to every registered token."""
        high = [n for n in notifications if getattr(n, "severity", "info") in _PUSH_SEVERITIES]
        if not self.enabled:
            return PushResult(0, 0, False, "FCM disabled (no FIREBASE_CREDENTIALS_PATH).")
        if not high or not tokens:
            return PushResult(0, 0, True, "Nothing high-priority to push.")

        access_token, project_id = self._credentials()
        if not access_token or not project_id:
            return PushResult(0, 0, True, "FCM enabled but credentials could not be loaded.")

        url = _FCM_ENDPOINT.format(project_id=project_id)
        headers = {
            "Authorization": f"Bearer {access_token}",
            "Content-Type": "application/json",
        }

        attempted = 0
        delivered = 0
        with httpx.Client(timeout=15.0) as client:
            for note in high:
                for token in tokens:
                    attempted += 1
                    if self._send_one(client, url, headers, token, note):
                        delivered += 1
        return PushResult(attempted, delivered, True, f"Pushed {delivered}/{attempted}.")

    def _send_one(self, client, url, headers, token, note) -> bool:
        payload = {
            "message": {
                "token": token,
                "notification": {"title": note.title, "body": note.detail},
                "data": {"severity": note.severity, "title": note.title},
                "android": {"priority": "high"},
            }
        }
        try:
            resp = client.post(url, headers=headers, content=json.dumps(payload))
            if resp.status_code == 200:
                return True
            logger.warning("FCM send failed (%s): %s", resp.status_code, resp.text[:200])
        except httpx.HTTPError as exc:
            logger.warning("FCM send error: %s", exc)
        return False

    def _credentials(self) -> tuple[str | None, str | None]:
        """Mint an OAuth2 access token from the service-account JSON.

        Returns ``(None, None)`` (and logs) if google-auth isn't installed or the
        credentials file can't be read — keeping the whole thing fail-safe.
        """
        try:
            from google.auth.transport.requests import Request  # type: ignore
            from google.oauth2 import service_account  # type: ignore
        except ImportError:
            logger.warning(
                "FCM enabled but google-auth is not installed. "
                "Add 'google-auth' to requirements.txt to enable push."
            )
            return None, None

        try:
            creds = service_account.Credentials.from_service_account_file(
                self.settings.firebase_credentials_path, scopes=[_FCM_SCOPE]
            )
            creds.refresh(Request())
            project_id = self.settings.firebase_project_id or getattr(creds, "project_id", "")
            return creds.token, project_id or None
        except Exception as exc:  # missing file, bad JSON, network, etc.
            logger.warning("Failed to load Firebase credentials: %s", exc)
            return None, None
