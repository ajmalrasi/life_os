from __future__ import annotations

import base64
import hashlib
from urllib.parse import parse_qs, urlsplit

from app.mail.oauth import GogOAuthService


class _Response:
    def __init__(self, payload: dict) -> None:
        self.payload = payload

    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict:
        return self.payload


class _HttpClient:
    def __init__(self, *_args, **_kwargs) -> None:
        pass

    def __enter__(self):
        return self

    def __exit__(self, *_args) -> None:
        return None

    def post(self, _url: str, *, data: dict) -> _Response:
        assert data["code_verifier"] == "v" * 64
        assert data["client_secret"] == "client-secret"
        return _Response({"access_token": "access-once", "refresh_token": "refresh-long"})

    def get(self, _url: str, *, headers: dict) -> _Response:
        assert headers == {"Authorization": "Bearer access-once"}
        return _Response({"emailAddress": "Selected@Example.com"})


class _Runner:
    def __init__(self) -> None:
        self.import_args: list[str] | None = None
        self.import_input: str | None = None

    def json(self, args: list[str], **kwargs) -> dict:
        self.import_args = args
        self.import_input = kwargs.get("input_text")
        return {"stored": True}


def _service(monkeypatch) -> tuple[GogOAuthService, _Runner]:
    service = GogOAuthService.__new__(GogOAuthService)
    runner = _Runner()
    service.runner = runner
    monkeypatch.setattr(
        service,
        "_client_credentials",
        lambda: ("client-id", "client-secret"),
    )
    return service, runner


def test_start_lets_google_choose_account_and_binds_pkce(monkeypatch):
    service, _runner = _service(monkeypatch)
    verifier = "v" * 64

    result = service.start(
        "http://127.0.0.1:53124/oauth2/callback",
        "session-id",
        verifier,
    )

    params = parse_qs(urlsplit(result.authorization_url).query)
    expected_challenge = base64.urlsafe_b64encode(
        hashlib.sha256(verifier.encode("ascii")).digest()
    ).rstrip(b"=").decode("ascii")
    assert params["state"] == ["session-id"]
    assert params["code_challenge"] == [expected_challenge]
    assert params["prompt"] == ["consent select_account"]
    assert "login_hint" not in params


def test_complete_uses_google_profile_and_imports_token_through_stdin(monkeypatch):
    service, runner = _service(monkeypatch)
    monkeypatch.setattr("app.mail.oauth.httpx.Client", _HttpClient)

    email = service.complete(
        None,
        "http://127.0.0.1:53124/oauth2/callback",
        "http://127.0.0.1:53124/oauth2/callback?code=once&state=session-id",
        "v" * 64,
    )

    assert email == "selected@example.com"
    assert runner.import_args == [
        "auth",
        "import",
        "--email",
        "selected@example.com",
        "--refresh-token-stdin",
        "--services",
        "gmail",
        "-j",
    ]
    assert runner.import_input == "refresh-long\n"
