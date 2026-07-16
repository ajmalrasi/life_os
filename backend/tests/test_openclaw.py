"""Reliability behavior for the local OpenAI-compatible model client."""

from __future__ import annotations

import httpx
import pytest

from app.services import openclaw
from app.services.openclaw import OpenClawClient, OpenClawTimeout


class _Response:
    def raise_for_status(self) -> None:
        pass

    def json(self) -> dict:
        return {"choices": [{"message": {"content": "ok"}}]}


def test_chat_caps_generation_tokens(monkeypatch):
    captured: dict = {}

    class FakeHttpClient:
        def __init__(self, timeout):
            captured["timeout"] = timeout

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def post(self, url, json, headers):
            captured["payload"] = json
            return _Response()

    monkeypatch.setattr(openclaw.httpx, "Client", FakeHttpClient)
    monkeypatch.setattr(openclaw.settings, "openclaw_max_tokens", 384)

    result = OpenClawClient(base_url="http://model").chat(
        [{"role": "user", "content": "hello"}], timeout=12.0
    )
    assert result == "ok"
    assert captured["timeout"] == 12.0
    assert captured["payload"]["max_tokens"] == 384


def test_timeout_is_distinguished_from_unreachable(monkeypatch):
    class TimeoutHttpClient:
        def __init__(self, timeout):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def post(self, url, json, headers):
            raise httpx.ReadTimeout("too slow")

    monkeypatch.setattr(openclaw.httpx, "Client", TimeoutHttpClient)

    with pytest.raises(OpenClawTimeout, match="timed out"):
        OpenClawClient(base_url="http://model").chat(
            [{"role": "user", "content": "hello"}]
        )
