"""PKCE-bound Gmail authorization completed and stored by the Jetson."""

from __future__ import annotations

import base64
import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import parse_qs, urlencode, urlsplit

import httpx

from app.config import Settings, get_settings
from app.mail.gog import GogCommandError, GogRunner


class MailAuthorizationError(RuntimeError):
    pass


@dataclass(frozen=True)
class RemoteAuthorization:
    authorization_url: str


class GogOAuthService:
    AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    TOKEN_URL = "https://oauth2.googleapis.com/token"
    PROFILE_URL = "https://gmail.googleapis.com/gmail/v1/users/me/profile"
    GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"

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

    def start(
        self,
        redirect_uri: str,
        state: str,
        code_verifier: str,
        login_hint: str | None = None,
    ) -> RemoteAuthorization:
        challenge = base64.urlsafe_b64encode(
            hashlib.sha256(code_verifier.encode("ascii")).digest()
        ).rstrip(b"=").decode("ascii")
        params = {
            "client_id": self._client_credentials()[0],
            "redirect_uri": redirect_uri,
            "response_type": "code",
            "scope": self.GMAIL_READONLY_SCOPE,
            "access_type": "offline",
            "prompt": "consent select_account",
            "include_granted_scopes": "false",
            "state": state,
            "code_challenge": challenge,
            "code_challenge_method": "S256",
        }
        if login_hint:
            params["login_hint"] = login_hint.strip().casefold()
        return RemoteAuthorization(f"{self.AUTHORIZE_URL}?{urlencode(params)}")

    def complete(
        self,
        expected_email: str | None,
        redirect_uri: str,
        callback_url: str,
        code_verifier: str,
    ) -> str:
        code = parse_qs(urlsplit(callback_url).query).get("code", [""])[0]
        if not code:
            raise MailAuthorizationError("Google callback is missing authorization data.")
        client_id, client_secret = self._client_credentials()
        try:
            with httpx.Client(timeout=30.0) as client:
                token_response = client.post(
                    self.TOKEN_URL,
                    data={
                        "client_id": client_id,
                        "client_secret": client_secret,
                        "code": code,
                        "code_verifier": code_verifier,
                        "grant_type": "authorization_code",
                        "redirect_uri": redirect_uri,
                    },
                )
                token_response.raise_for_status()
                tokens = token_response.json()
                access_token = str(tokens.get("access_token") or "")
                refresh_token = str(tokens.get("refresh_token") or "")
                if not access_token or not refresh_token:
                    raise MailAuthorizationError(
                        "Google did not return a reusable Gmail authorization. Start again."
                    )
                profile_response = client.get(
                    self.PROFILE_URL,
                    headers={"Authorization": f"Bearer {access_token}"},
                )
                profile_response.raise_for_status()
                email = (
                    str(profile_response.json().get("emailAddress") or "")
                    .strip()
                    .casefold()
                )
        except MailAuthorizationError:
            raise
        except (httpx.HTTPError, ValueError) as exc:
            raise MailAuthorizationError("Google could not complete Gmail authorization.") from exc

        if not email or "@" not in email:
            raise MailAuthorizationError("Google did not return the authorized Gmail address.")
        expected = (expected_email or "").strip().casefold()
        if expected and email != expected:
            raise MailAuthorizationError(
                f"Google authorized {email}, but {expected} was selected for reauthorization."
            )
        try:
            self.runner.json(
                [
                    "auth", "import",
                    "--email", email,
                    "--refresh-token-stdin",
                    "--services", "gmail",
                    "-j",
                ],
                input_text=f"{refresh_token}\n",
                timeout=45,
            )
        except GogCommandError as exc:
            raise MailAuthorizationError(self._safe_error(exc)) from exc
        return email

    def _client_credentials(self) -> tuple[str, str]:
        try:
            status = self.runner.json(["auth", "status", "-j"])
            path = str(status.get("account", {}).get("credentials_path") or "")
            data = json.loads(Path(path).read_text(encoding="utf-8"))
        except (GogCommandError, OSError, ValueError) as exc:
            raise MailAuthorizationError("LifeOS Gmail OAuth credentials are unavailable.") from exc
        container = data.get("installed") or data.get("web") or data
        client_id = str(container.get("client_id") or "") if isinstance(container, dict) else ""
        client_secret = (
            str(container.get("client_secret") or "") if isinstance(container, dict) else ""
        )
        if not client_id:
            raise MailAuthorizationError("LifeOS Gmail OAuth credentials are incomplete.")
        if not client_secret:
            client = str(status.get("account", {}).get("client") or "default")
            try:
                client_secret = self.runner.client_secret(client)
            except GogCommandError as exc:
                raise MailAuthorizationError(
                    "LifeOS Gmail OAuth client secret is unavailable."
                ) from exc
        return client_id, client_secret

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
