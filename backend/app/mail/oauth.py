"""PKCE-bound remote Gmail authorization completed by the Jetson."""

from __future__ import annotations

from dataclasses import dataclass

from app.config import Settings, get_settings
from app.mail.gog import GogCommandError, GogRunner


class MailAuthorizationError(RuntimeError):
    pass


@dataclass(frozen=True)
class RemoteAuthorization:
    authorization_url: str


class GogOAuthService:
    def __init__(self, settings: Settings | None = None) -> None:
        settings = settings or get_settings()
        self.runner = GogRunner(
            keyring_password=settings.gog_keyring_password,
            gog_bin=settings.gog_bin,
            home=settings.gog_home or None,
        )

    def authorized_accounts(self) -> set[str]:
        if not self.runner.available:
            return set()
        try:
            data = self.runner.json(["auth", "list", "-j"])
        except GogCommandError:
            return set()
        return {
            str(row.get("email") or "").strip().casefold()
            for row in data.get("accounts", [])
            if isinstance(row, dict) and row.get("email")
        }

    def start(self, email: str, redirect_uri: str) -> RemoteAuthorization:
        try:
            data = self.runner.json(
                [
                    "auth", "add", email,
                    "--services", "gmail",
                    "--gmail-scope", "readonly",
                    "--remote", "--step", "1",
                    "--redirect-uri", redirect_uri,
                    "--force-consent",
                    "-j",
                ],
                timeout=30,
            )
        except GogCommandError as exc:
            raise MailAuthorizationError(self._safe_error(exc)) from exc
        authorization_url = str(data.get("auth_url") or "")
        if not authorization_url.startswith("https://accounts.google.com/"):
            raise MailAuthorizationError("Google did not return a valid authorization link.")
        return RemoteAuthorization(authorization_url=authorization_url)

    def complete(self, email: str, redirect_uri: str, callback_url: str) -> str:
        try:
            data = self.runner.json(
                [
                    "auth", "add", email,
                    "--services", "gmail",
                    "--gmail-scope", "readonly",
                    "--remote", "--step", "2",
                    "--auth-url", callback_url,
                    "--redirect-uri", redirect_uri,
                    "--force-consent",
                    "-j",
                ],
                timeout=45,
            )
        except GogCommandError as exc:
            raise MailAuthorizationError(self._safe_error(exc)) from exc
        authorized = str(data.get("email") or "").strip().casefold()
        if authorized != email.strip().casefold():
            raise MailAuthorizationError("Google authorized a different Gmail account.")
        return authorized

    @staticmethod
    def _safe_error(exc: GogCommandError) -> str:
        lowered = exc.stderr.casefold()
        if "access_denied" in lowered or "authorization was denied" in lowered:
            return "Google authorization was cancelled."
        if "invalid_grant" in lowered or "state" in lowered:
            return "The authorization link expired. Start the connection again."
        if "read-only file system" in lowered or "permission denied" in lowered:
            return "LifeOS cannot save Gmail authorization on the Jetson."
        return "Gmail authorization could not be completed."
