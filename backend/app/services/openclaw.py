"""OpenClaw client.

Talks to a locally hosted OpenClaw server (OpenAI-compatible chat completions)
running on the Jetson Orin Nano. Base URL, model, and key are all configurable.

If the server is unreachable we raise ``OpenClawUnavailable`` so the API layer
can degrade gracefully instead of fabricating an answer.
"""

from __future__ import annotations

import json
import re
import threading
from contextlib import contextmanager
from collections.abc import Iterator
from dataclasses import dataclass
from enum import IntEnum
from time import monotonic

import httpx

from app.config import get_settings

settings = get_settings()

_PY_TYPES = {"string": str, "number": (int, float), "integer": int, "boolean": bool}


class OpenClawUnavailable(RuntimeError):
    pass


class OpenClawTimeout(OpenClawUnavailable):
    """The local model was reachable but did not finish in time."""

    pass


class OpenClawBusy(OpenClawUnavailable):
    """The model is reserved for a higher-priority or already-running request."""

    pass


class OpenClawRequestRejected(OpenClawUnavailable):
    """The local model answered, but rejected this request (HTTP 4xx)."""

    pass


class OpenClawTruncated(OpenClawUnavailable):
    """Generation reached the requested/output-capacity limit."""

    pass


class InferenceWorkload(IntEnum):
    """Admission priority for the Jetson's single-sequence local model."""

    INTERACTIVE = 0
    FINANCE = 10
    BACKGROUND = 20


_WORKLOAD_TIMEOUT_CAPS = {
    InferenceWorkload.FINANCE: 30.0,
    InferenceWorkload.BACKGROUND: 45.0,
}


@dataclass(frozen=True)
class _InferenceWaiter:
    priority: InferenceWorkload
    sequence: int


class InferenceCoordinator:
    """Serialize model calls and let interactive work jump ahead in the queue.

    MLC is configured for a single sequence, so concurrent HTTP requests only
    create contention and unpredictable latency. Background/finance callers use
    fail-fast admission and can safely defer or fall back; interactive Ask calls
    wait for the active request, then run before any lower-priority waiter.
    """

    def __init__(self) -> None:
        self._condition = threading.Condition()
        self._active: _InferenceWaiter | None = None
        self._waiters: list[_InferenceWaiter] = []
        self._sequence = 0

    @contextmanager
    def lease(self, priority: InferenceWorkload, wait_timeout: float) -> Iterator[float]:
        started = monotonic()
        deadline = started + max(0.0, wait_timeout)
        with self._condition:
            self._sequence += 1
            waiter = _InferenceWaiter(priority=priority, sequence=self._sequence)
            self._waiters.append(waiter)
            while self._active is not None or waiter != min(
                self._waiters, key=lambda item: (item.priority, item.sequence)
            ):
                remaining = deadline - monotonic()
                if remaining <= 0:
                    self._waiters.remove(waiter)
                    self._condition.notify_all()
                    raise OpenClawBusy(
                        f"Local model is busy ({priority.name.casefold()} request deferred)"
                    )
                self._condition.wait(timeout=remaining)
            self._waiters.remove(waiter)
            self._active = waiter

        try:
            yield monotonic() - started
        finally:
            with self._condition:
                if self._active == waiter:
                    self._active = None
                self._condition.notify_all()

    def status(self) -> dict[str, int | str | None]:
        with self._condition:
            return {
                "active_workload": (
                    self._active.priority.name.casefold() if self._active else None
                ),
                "waiting_interactive": sum(
                    waiter.priority == InferenceWorkload.INTERACTIVE for waiter in self._waiters
                ),
                "waiting_total": len(self._waiters),
            }


_INFERENCE_COORDINATOR = InferenceCoordinator()


def _completion_message(data: dict) -> dict:
    """Return a completed message, rejecting silently truncated output."""
    try:
        choice = data["choices"][0]
        if choice.get("finish_reason") == "length":
            raise OpenClawTruncated("Local model output reached its token limit")
        return choice["message"]
    except OpenClawTruncated:
        raise
    except (KeyError, IndexError, TypeError) as exc:
        raise OpenClawUnavailable(f"Unexpected OpenClaw response shape: {data}") from exc


def _translate_http_error(exc: httpx.HTTPStatusError, operation: str) -> OpenClawUnavailable:
    status = exc.response.status_code
    if 400 <= status < 500:
        detail = exc.response.text[:400]
        return OpenClawRequestRejected(f"OpenClaw rejected {operation} (HTTP {status}): {detail}")
    return OpenClawUnavailable(f"OpenClaw {operation} failed: {exc}")


def _strip_think(text: str) -> str:
    """Drop a leading ``<think>...</think>`` reasoning block, if present.

    Kept as defense: the openclaw model varies by host and a thinking variant
    (which prefixes replies with its chain-of-thought) can be swapped in on the
    Jetson. A no-op on the non-thinking models. Callers only want the answer.
    """
    if not text:
        return text
    end = text.rfind("</think>")
    if end == -1:
        return _strip_control_tokens(text)
    return _strip_control_tokens(text[end + len("</think>") :]).strip()


_CONTROL_TOKEN = re.compile(
    r"<(?:(?:\||l)?im_(?:start|end)(?:\||l)?|\|?(?:start|end)_of_turn\|?|"
    r"\|?(?:system|user|assistant)\|?)>\s*(?:system|user|assistant)?\s*",
    re.IGNORECASE,
)


def _strip_control_tokens(text: str) -> str:
    """Remove chat-template markers that some local Qwen builds emit as text."""
    return _CONTROL_TOKEN.sub("", text)


def _describe_schema(schema: dict) -> str:
    """Render a JSON schema as plain-language instructions.

    The Jetson's MLC-LLM server rejects ``response_format: json_schema`` (HTTP
    400) — it can't enforce enum/strict shape server-side — so the schema is
    spelled out in the prompt and validated client-side instead. See
    ``chat_structured``.
    """
    lines = ["Respond with ONLY a single JSON object (no other text), with exactly these fields:"]
    required = set(schema.get("required", []))
    for name, spec in schema.get("properties", {}).items():
        bits = [spec.get("type", "any")]
        if "enum" in spec:
            bits.append("one of: " + ", ".join(str(v) for v in spec["enum"]))
        marker = "required" if name in required else "optional"
        lines.append(f'- "{name}" ({", ".join(bits)}, {marker})')
    return "\n".join(lines)


def _inject_schema_instructions(messages: list[dict[str, str]], schema: dict) -> list[dict[str, str]]:
    instructions = _describe_schema(schema)
    messages = list(messages)
    for i, msg in enumerate(messages):
        if msg.get("role") == "system":
            messages[i] = {**msg, "content": msg["content"] + "\n\n" + instructions}
            return messages
    return [{"role": "system", "content": instructions}] + messages


def _matches_schema(raw: str, schema: dict) -> bool:
    """Best-effort check that ``raw`` is JSON matching ``schema``'s properties/
    required/enum constraints (the subset of JSON schema this client's callers
    use) — used to decide whether a ``chat_structured`` reply needs a retry."""
    try:
        data = json.loads(raw)
    except (ValueError, TypeError):
        return False
    if not isinstance(data, dict):
        return False
    for name in schema.get("required", []):
        if name not in data:
            return False
    properties = schema.get("properties", {})
    if schema.get("additionalProperties") is False and not set(data).issubset(properties):
        return False
    for name, spec in properties.items():
        if name not in data:
            continue
        value = data[name]
        py_type = _PY_TYPES.get(spec.get("type"))
        if py_type and not isinstance(value, py_type):
            return False
        if "enum" in spec and value not in spec["enum"]:
            return False
    return True


class OpenClawClient:
    def __init__(
        self,
        base_url: str | None = None,
        model: str | None = None,
        workload: InferenceWorkload = InferenceWorkload.BACKGROUND,
    ) -> None:
        self.base_url = (base_url or settings.openclaw_base_url).rstrip("/")
        self.model = model or settings.openclaw_model
        self.timeout = settings.openclaw_timeout_seconds
        self.workload = workload

    @contextmanager
    def _admitted(self, timeout: float | None) -> Iterator[float]:
        request_timeout = float(timeout or self.timeout)
        cap = _WORKLOAD_TIMEOUT_CAPS.get(self.workload)
        if cap is not None:
            request_timeout = min(request_timeout, cap)
        # Gmail/analytics callers are explicitly best-effort: if the model is
        # occupied they defer or use deterministic fallbacks instead of forming
        # a backlog. Ask may wait, but its queue time counts against the same
        # end-to-end timeout budget as generation.
        wait_timeout = (
            request_timeout if self.workload == InferenceWorkload.INTERACTIVE else 0.0
        )
        with _INFERENCE_COORDINATOR.lease(self.workload, wait_timeout) as waited:
            remaining = (
                request_timeout - waited
                if self.workload == InferenceWorkload.INTERACTIVE
                else request_timeout
            )
            if remaining <= 0:
                raise OpenClawBusy("Local model remained busy until the request deadline")
            yield remaining

    def _headers(self) -> dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if settings.openclaw_api_key:
            headers["Authorization"] = f"Bearer {settings.openclaw_api_key}"
        return headers

    def chat(
        self,
        messages: list[dict[str, str]],
        temperature: float = 0.2,
        timeout: float | None = None,
    ) -> str:
        """Call /v1/chat/completions and return the assistant text."""
        url = f"{self.base_url}/v1/chat/completions"
        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": temperature,
            "stream": False,
            "max_tokens": settings.openclaw_max_tokens,
        }
        try:
            with self._admitted(timeout) as request_timeout:
                with httpx.Client(timeout=request_timeout) as client:
                    resp = client.post(url, json=payload, headers=self._headers())
                    resp.raise_for_status()
                    data = resp.json()
        except httpx.TimeoutException as exc:
            raise OpenClawTimeout(f"Local model timed out: {exc}") from exc
        except httpx.HTTPStatusError as exc:
            raise _translate_http_error(exc, "request") from exc
        except (httpx.HTTPError, ValueError) as exc:  # network error or bad JSON
            raise OpenClawUnavailable(f"OpenClaw request failed: {exc}") from exc

        return _strip_think(_completion_message(data).get("content") or "")

    def stream_chat(
        self,
        messages: list[dict],
        tools: list[dict] | None = None,
        temperature: float = 0.2,
        timeout: float | None = None,
    ) -> Iterator[dict]:
        """Stream one OpenAI-compatible completion.

        Yields ``content`` events as soon as visible text arrives, followed by
        one ``message`` event containing the assembled content/tool calls. Tool
        call argument fragments are joined by their OpenAI stream index so the
        normal bounded agent loop can keep working without delaying text-only
        answers until the whole generation finishes.
        """
        url = f"{self.base_url}/v1/chat/completions"
        payload: dict = {
            "model": self.model,
            "messages": messages,
            "temperature": temperature,
            "stream": True,
            "max_tokens": settings.openclaw_max_tokens,
        }
        if tools:
            payload["tools"] = tools
            payload["tool_choice"] = "auto"

        content_parts: list[str] = []
        calls: dict[int, dict] = {}
        finish_reason: str | None = None
        visibility = _StreamingThinkFilter()
        controls = _StreamingControlTokenFilter()
        try:
            with self._admitted(timeout) as request_timeout:
                with httpx.Client(timeout=request_timeout) as client:
                    with client.stream("POST", url, json=payload, headers=self._headers()) as resp:
                        resp.raise_for_status()
                        for line in resp.iter_lines():
                            if not line or line.startswith(":"):
                                continue
                            raw = line[5:].strip() if line.startswith("data:") else line.strip()
                            if raw == "[DONE]":
                                break
                            try:
                                data = json.loads(raw)
                                choice = data["choices"][0]
                                delta = choice.get("delta") or {}
                                finish_reason = choice.get("finish_reason") or finish_reason
                            except (ValueError, KeyError, IndexError, TypeError) as exc:
                                raise OpenClawUnavailable(
                                    f"Unexpected OpenClaw stream event: {raw[:200]}"
                                ) from exc

                            piece = delta.get("content") or ""
                            if piece:
                                visible = visibility.push(piece)
                                if visible:
                                    clean = controls.push(visible)
                                    if clean:
                                        content_parts.append(clean)
                                        yield {"type": "content", "text": clean}

                            for fragment in delta.get("tool_calls") or []:
                                index = int(fragment.get("index", 0))
                                call = calls.setdefault(
                                    index,
                                    {"id": "", "type": "function", "function": {"name": "", "arguments": ""}},
                                )
                                if fragment.get("id"):
                                    call["id"] = fragment["id"]
                                fn = fragment.get("function") or {}
                                call["function"]["name"] += fn.get("name") or ""
                                call["function"]["arguments"] += fn.get("arguments") or ""
        except httpx.TimeoutException as exc:
            raise OpenClawTimeout(f"Local model timed out: {exc}") from exc
        except httpx.HTTPStatusError as exc:
            raise _translate_http_error(exc, "streaming request") from exc
        except httpx.HTTPError as exc:
            raise OpenClawUnavailable(f"OpenClaw streaming request failed: {exc}") from exc

        if finish_reason == "length":
            raise OpenClawTruncated("Local model output reached its token limit")

        tail = visibility.finish()
        if tail:
            clean = controls.push(tail)
            if clean:
                content_parts.append(clean)
                yield {"type": "content", "text": clean}
        clean_tail = controls.finish()
        if clean_tail:
            content_parts.append(clean_tail)
            yield {"type": "content", "text": clean_tail}
        yield {
            "type": "message",
            "message": {
                "role": "assistant",
                "content": "".join(content_parts),
                "tool_calls": [calls[i] for i in sorted(calls)],
            },
        }

    def chat_structured(
        self,
        messages: list[dict[str, str]],
        schema: dict,
        schema_name: str = "response",
        temperature: float = 0.0,
    ) -> str:
        """Call /v1/chat/completions asking for JSON matching ``schema`` and
        return the raw JSON text.

        The Jetson's MLC-LLM server has a broken ``response_format:
        json_object`` grammar constraint (it was observed to mangle even a
        trivial ``{"a": 1}`` request into a malformed array) and doesn't
        support ``json_schema`` at all. So this makes a plain unconstrained
        call, with the schema spelled out in the prompt instead, and validates
        the reply against it here; a reply that doesn't match gets one
        corrective retry. Callers still get back raw JSON text and do their
        own parsing/enum checks, same as before.
        """
        schema_messages = _inject_schema_instructions(messages, schema)
        raw = self.chat(schema_messages, temperature)
        if _matches_schema(raw, schema):
            return raw

        retry_messages = schema_messages + [
            {"role": "assistant", "content": raw},
            {
                "role": "user",
                "content": (
                    "That response didn't match the required JSON shape. "
                    + _describe_schema(schema)
                    + "\nReturn ONLY the corrected JSON object."
                ),
            },
        ]
        return self.chat(retry_messages, temperature)

    def chat_with_tools(
        self,
        messages: list[dict],
        tools: list[dict],
        temperature: float = 0.2,
        timeout: float | None = None,
    ) -> dict:
        """Call /v1/chat/completions with function tools; return the raw message.

        The returned dict is the assistant message — it may contain ``tool_calls``
        (the model wants to invoke a tool) or ``content`` (a final answer). Servers
        that don't support tools simply return ``content`` and the agent loop ends.
        """
        url = f"{self.base_url}/v1/chat/completions"
        payload = {
            "model": self.model,
            "messages": messages,
            "tools": tools,
            "tool_choice": "auto",
            "temperature": temperature,
            "stream": False,
            "max_tokens": settings.openclaw_max_tokens,
        }
        try:
            with self._admitted(timeout) as request_timeout:
                with httpx.Client(timeout=request_timeout) as client:
                    resp = client.post(url, json=payload, headers=self._headers())
                    resp.raise_for_status()
                    data = resp.json()
        except httpx.TimeoutException as exc:
            raise OpenClawTimeout(f"Local model timed out: {exc}") from exc
        except httpx.HTTPStatusError as exc:
            raise _translate_http_error(exc, "tool request") from exc
        except (httpx.HTTPError, ValueError) as exc:
            raise OpenClawUnavailable(f"OpenClaw request failed: {exc}") from exc

        message = _completion_message(data)
        if message.get("content"):
            message["content"] = _strip_think(message["content"])
        return message

    def health(self) -> bool:
        try:
            with httpx.Client(timeout=5.0) as client:
                resp = client.get(f"{self.base_url}/v1/models", headers=self._headers())
                return resp.status_code < 500
        except httpx.HTTPError:
            return False

    @staticmethod
    def admission_status() -> dict[str, int | str | None]:
        return _INFERENCE_COORDINATOR.status()


class _StreamingThinkFilter:
    """Hide a leading Qwen ``<think>`` block without delaying normal output."""

    def __init__(self) -> None:
        self.buffer = ""
        self.mode: str | None = None

    def push(self, text: str) -> str:
        if self.mode == "visible":
            return text
        self.buffer += text
        if self.mode == "hidden":
            return self._finish_hidden_if_ready()

        stripped = self.buffer.lstrip()
        if "<think>".startswith(stripped):
            return ""
        if stripped.startswith("<think>"):
            self.mode = "hidden"
            return self._finish_hidden_if_ready()

        self.mode = "visible"
        visible, self.buffer = self.buffer, ""
        return visible

    def _finish_hidden_if_ready(self) -> str:
        end = self.buffer.rfind("</think>")
        if end == -1:
            return ""
        visible = self.buffer[end + len("</think>") :].lstrip()
        self.buffer = ""
        self.mode = "visible"
        return visible

    def finish(self) -> str:
        if self.mode == "hidden":
            return ""
        tail, self.buffer = self.buffer, ""
        return tail


class _StreamingControlTokenFilter:
    """Strip control tokens even when a token is split across SSE chunks."""

    _TAIL = 64

    def __init__(self) -> None:
        self.buffer = ""

    def push(self, text: str) -> str:
        self.buffer += text
        if len(self.buffer) <= self._TAIL:
            return ""
        cut = len(self.buffer) - self._TAIL
        # If a possible marker begins near the cut, retain it until the next
        # chunk so a split token can never leak its first half.
        last_open = self.buffer.rfind("<", max(0, cut - self._TAIL), cut)
        if last_open != -1:
            cut = last_open
        stable, self.buffer = self.buffer[:cut], self.buffer[cut:]
        return _strip_control_tokens(stable)

    def finish(self) -> str:
        clean = _strip_control_tokens(self.buffer)
        self.buffer = ""
        return clean
