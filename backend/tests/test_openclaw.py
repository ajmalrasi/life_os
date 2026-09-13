"""Reliability behavior for the local OpenAI-compatible model client."""

from __future__ import annotations

import httpx
import pytest
import threading
import time

from app.services import openclaw
from app.services.openclaw import (
    InferenceCoordinator,
    InferenceWorkload,
    OpenClawBusy,
    OpenClawClient,
    OpenClawTimeout,
)


class _Response:
    def raise_for_status(self) -> None:
        pass

    def json(self) -> dict:
        return {"choices": [{"message": {"content": "ok"}}]}


def test_token_counter_preserves_tools_and_tool_results(monkeypatch):
    captured = {}
    class CounterHttp:
        def __init__(self, timeout):
            pass
        def __enter__(self):
            return self
        def __exit__(self, *args):
            pass
        def post(self, url, json, headers):
            captured.update(json)
            return httpx.Response(200, json={"input_tokens": 123},
                                  request=httpx.Request("POST", url))
    monkeypatch.setattr(openclaw.httpx, "Client", CounterHttp)
    count = OpenClawClient().count_chat_tokens([
        {"role": "system", "content": "system"},
        {"role": "user", "content": "my spend"},
        {"role": "assistant", "tool_calls": [{"id": "t", "function": {
            "name": "query_money", "arguments": '{"category":"food"}'}}]},
        {"role": "tool", "tool_call_id": "t", "name": "query_money", "content": '{"total":12}'},
    ], [{"type": "function", "function": {"name": "query_money",
          "description": "Money", "parameters": {"type": "object"}}}])
    assert count == 123
    assert captured["thinking"]["type"] == "disabled"
    assert captured["messages"][1]["content"][0]["input"] == {"category": "food"}
    assert captured["messages"][2]["content"][0]["tool_use_id"] == "t"
    assert captured["tools"][0]["input_schema"] == {"type": "object"}


@pytest.mark.parametrize("finished", [True, False])
def test_stream_requires_completion_marker_and_accepts_ask_budget(monkeypatch, finished):
    import json
    captured = {}
    chunks = [{"choices": [{"delta": {"content": "Hello"}, "finish_reason": None}]}]
    if finished:
        chunks.append({"choices": [{"delta": {}, "finish_reason": "stop"}]})
    content = "\n".join("data: "+json.dumps(c) for c in chunks) + "\ndata: [DONE]\n"
    class StreamHttp:
        def __init__(self, timeout):
            pass
        def __enter__(self):
            return self
        def __exit__(self, *args):
            pass
        def stream(self, method, url, json, headers):
            from contextlib import contextmanager
            captured.update(json)
            @contextmanager
            def response():
                yield httpx.Response(200, text=content, request=httpx.Request("POST", url))
            return response()
    monkeypatch.setattr(openclaw.httpx, "Client", StreamHttp)
    stream = OpenClawClient().stream_chat([{"role": "user", "content": "Hi"}],
                                          max_tokens=1024, enable_thinking=False)
    if finished:
        events = list(stream)
        assert events[-1]["message"]["content"] == "Hello"
    else:
        with pytest.raises(openclaw.OpenClawUnavailable, match="completion marker"):
            list(stream)
    assert captured["max_tokens"] == 1024 and captured["enable_thinking"] is False


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
    monkeypatch.setattr(openclaw.settings, "openclaw_max_tokens", 512)

    result = OpenClawClient(base_url="http://model").chat(
        [{"role": "user", "content": "hello"}], timeout=12.0
    )
    assert result == "ok"
    assert captured["timeout"] == 12.0
    assert captured["payload"]["max_tokens"] == 512


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


def test_inference_coordinator_runs_only_one_request_and_prioritizes_ask():
    coordinator = InferenceCoordinator()
    active_started = threading.Event()
    release_active = threading.Event()
    order: list[str] = []

    def active_finance() -> None:
        with coordinator.lease(InferenceWorkload.FINANCE, wait_timeout=0):
            active_started.set()
            assert release_active.wait(timeout=2)

    def waiting(priority: InferenceWorkload, label: str) -> None:
        with coordinator.lease(priority, wait_timeout=2):
            order.append(label)

    active = threading.Thread(target=active_finance)
    background = threading.Thread(
        target=waiting, args=(InferenceWorkload.BACKGROUND, "background")
    )
    interactive = threading.Thread(
        target=waiting, args=(InferenceWorkload.INTERACTIVE, "interactive")
    )

    active.start()
    assert active_started.wait(timeout=1)
    background.start()
    while coordinator.status()["waiting_total"] < 1:
        time.sleep(0.005)
    interactive.start()
    while coordinator.status()["waiting_total"] < 2:
        time.sleep(0.005)
    release_active.set()

    active.join(timeout=2)
    background.join(timeout=2)
    interactive.join(timeout=2)
    assert not active.is_alive()
    assert not background.is_alive()
    assert not interactive.is_alive()
    assert order == ["interactive", "background"]


def test_background_inference_defers_instead_of_waiting():
    coordinator = InferenceCoordinator()
    with coordinator.lease(InferenceWorkload.INTERACTIVE, wait_timeout=0):
        with pytest.raises(OpenClawBusy, match="background request deferred"):
            with coordinator.lease(InferenceWorkload.BACKGROUND, wait_timeout=0):
                pytest.fail("background request should not run while Ask owns the model")


def test_finance_inference_has_a_short_runtime_cap(monkeypatch):
    captured: dict = {}

    class FakeHttpClient:
        def __init__(self, timeout):
            captured["timeout"] = timeout

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def post(self, url, json, headers):
            return _Response()

    monkeypatch.setattr(openclaw.httpx, "Client", FakeHttpClient)
    client = OpenClawClient(
        base_url="http://model", workload=InferenceWorkload.FINANCE
    )
    client.chat([{"role": "user", "content": "hello"}], timeout=90.0)

    assert captured["timeout"] == 30.0
