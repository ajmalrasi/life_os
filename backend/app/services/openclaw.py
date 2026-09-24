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
import socket
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


class OpenClawEngineError(OpenClawUnavailable):
    """The runtime accepted the request but generation failed (HTTP 500 engine_error)."""

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
    """Bound concurrent model calls to the runtime's batch size and let
    interactive work jump ahead in the queue.

    The EdgeLLM engine decodes ``capacity`` sequences together (batch 2 on the
    Jetson); more concurrent HTTP requests only queue inside the server with
    unpredictable latency. Background/finance callers use fail-fast (or short)
    admission and can safely defer; interactive Ask calls wait for a free slot,
    then run before any lower-priority waiter.
    """

    def __init__(self, capacity: int = 1) -> None:
        self._condition = threading.Condition()
        self._capacity = max(1, capacity)
        self._active: list[_InferenceWaiter] = []
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
            while len(self._active) >= self._capacity or waiter != min(
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
            self._active.append(waiter)
            # Another slot may still be free for the next waiter in line.
            self._condition.notify_all()

        try:
            yield monotonic() - started
        finally:
            with self._condition:
                if waiter in self._active:
                    self._active.remove(waiter)
                self._condition.notify_all()

    def status(self) -> dict[str, int | str | None]:
        with self._condition:
            return {
                "active_workload": (
                    min(self._active, key=lambda item: item.priority).priority.name.casefold()
                    if self._active
                    else None
                ),
                "active_total": len(self._active),
                "capacity": self._capacity,
                "waiting_interactive": sum(
                    waiter.priority == InferenceWorkload.INTERACTIVE for waiter in self._waiters
                ),
                "waiting_total": len(self._waiters),
            }


_INFERENCE_COORDINATOR = InferenceCoordinator(settings.openclaw_max_concurrency)


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
        options = spec.get("anyOf") or [spec]
        bits = [" or ".join(option.get("type", "any") for option in options)]
        if "enum" in spec:
            bits.append("one of: " + ", ".join(str(v) for v in spec["enum"]))
        for option in options:
            if option.get("pattern") == r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}$":
                bits.append("formatted YYYY-MM-DDTHH:MM")
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


def _json_object(content: str) -> dict:
    text = _strip_think(content).strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text)
    try:
        parsed = json.loads(text)
    except ValueError as exc:
        raise OpenClawUnavailable("Local model returned invalid JSON") from exc
    if not isinstance(parsed, dict):
        raise OpenClawUnavailable("Local model returned a non-object JSON value")
    return parsed


def _matches_json_schema(value, schema: dict) -> bool:
    """Validate the JSON-schema subset used with ``chat_json`` (type, enum,
    anyOf, pattern, properties/required/additionalProperties)."""
    if "anyOf" in schema:
        return any(_matches_json_schema(value, option) for option in schema["anyOf"])
    kind = schema.get("type")
    if kind == "null":
        if value is not None:
            return False
    elif kind == "boolean":
        if not isinstance(value, bool):
            return False
    elif kind in {"integer", "number"}:
        if isinstance(value, bool) or not isinstance(value, _PY_TYPES[kind]):
            return False
    elif kind == "string":
        if not isinstance(value, str):
            return False
        if "pattern" in schema and not re.search(schema["pattern"], value):
            return False
    elif kind == "object":
        if not isinstance(value, dict):
            return False
        properties = schema.get("properties", {})
        if any(name not in value for name in schema.get("required", [])):
            return False
        if schema.get("additionalProperties") is False and not set(value).issubset(properties):
            return False
        if not all(_matches_json_schema(value[name], spec) for name, spec in properties.items() if name in value):
            return False
    if "enum" in schema and value not in schema["enum"]:
        return False
    return True


class OpenClawClient:
    def __init__(
        self,
        base_url: str | None = None,
        model: str | None = None,
        workload: InferenceWorkload = InferenceWorkload.BACKGROUND,
        admission_wait: float = 0.0,
    ) -> None:
        self.base_url = (base_url or settings.openclaw_base_url).rstrip("/")
        self.model = model or settings.openclaw_model
        self.timeout = settings.openclaw_timeout_seconds
        self.workload = workload
        # Seconds a non-interactive caller may queue for a free batch slot
        # before deferring. Queue time does not count against generation.
        self.admission_wait = max(0.0, admission_wait)

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
            request_timeout
            if self.workload == InferenceWorkload.INTERACTIVE
            else self.admission_wait
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

    def count_chat_tokens(self, messages: list[dict], tools: list[dict] | None = None,
                          timeout: float = 5.0, enable_thinking: bool = False) -> int:
        """Use EdgeLLM's tokenizer/template through its non-generating count API.

        Translate OpenAI messages to the Anthropic envelope accepted by the
        endpoint. EdgeLLM converts it back before using the same prompt renderer.
        Thinking is disabled in both counting and Ask generation.
        """
        body: dict = {"model": self.model, "messages": [],
                      "thinking": {"type": "enabled" if enable_thinking else "disabled",
                                   "budget_tokens": 1024}}
        for message in messages:
            role = message["role"]
            if role == "system":
                body["system"] = message["content"]
                continue
            if role == "tool":
                body["messages"].append({"role": "user", "content": [{
                    "type": "tool_result", "tool_use_id": message["tool_call_id"],
                    "content": message["content"]}]})
                continue
            blocks = []
            if message.get("content"):
                blocks.append({"type": "text", "text": message["content"]})
            for call in message.get("tool_calls") or []:
                fn = call["function"]
                blocks.append({"type": "tool_use", "id": call["id"],
                               "name": fn["name"], "input": json.loads(fn["arguments"])})
            body["messages"].append({"role": role, "content": blocks})
        if tools:
            body["tools"] = [
                {"name": t["function"]["name"],
                 "description": t["function"].get("description", ""),
                 "input_schema": t["function"]["parameters"]}
                for t in tools
            ]
            body["tool_choice"] = {"type": "auto"}
        try:
            with httpx.Client(timeout=timeout) as client:
                response = client.post(self.base_url + "/v1/messages/count_tokens",
                                       json=body, headers=self._headers())
                response.raise_for_status()
                count = response.json()["input_tokens"]
                if not isinstance(count, int) or count <= 0:
                    raise ValueError("Tokenizer returned no count")
                return count
        except (httpx.HTTPError, ValueError, KeyError, TypeError) as exc:
            raise OpenClawUnavailable("Could not count the local model input") from exc

    def chat(
        self,
        messages: list[dict[str, str]],
        temperature: float = 0.2,
        timeout: float | None = None,
        max_tokens: int | None = None,
    ) -> str:
        """Call /v1/chat/completions and return the assistant text."""
        url = f"{self.base_url}/v1/chat/completions"
        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": temperature,
            "stream": False,
            "max_tokens": max_tokens or settings.openclaw_max_tokens,
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
        max_tokens: int | None = None,
        enable_thinking: bool | None = None,
        cancel_event: threading.Event | None = None,
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
            "max_tokens": max_tokens or settings.openclaw_max_tokens,
        }
        if enable_thinking is not None:
            payload["enable_thinking"] = enable_thinking
        if tools:
            payload["tools"] = tools
            payload["tool_choice"] = "auto"

        content_parts: list[str] = []
        calls: dict[int, dict] = {}
        finish_reason: str | None = None
        visibility = _StreamingThinkFilter()
        controls = _StreamingControlTokenFilter()
        deadline = monotonic() + (timeout or self.timeout)
        try:
            with self._admitted(timeout) as request_timeout:
                with httpx.Client(timeout=request_timeout) as client:
                    with client.stream("POST", url, json=payload, headers=self._headers()) as resp:
                        resp.raise_for_status()
                        finished = threading.Event()
                        def interrupt_read():
                            # httpx read timeouts reset on each token. Interrupt a
                            # stalled socket at the absolute deadline or cancellation.
                            while not finished.wait(0.1):
                                if monotonic() >= deadline or (cancel_event and cancel_event.is_set()):
                                    network = resp.extensions.get("network_stream")
                                    sock = network.get_extra_info("socket") if network else None
                                    try:
                                        if sock:
                                            sock.shutdown(socket.SHUT_RDWR)
                                        else:
                                            resp.close()
                                    except (OSError, RuntimeError):
                                        pass
                                    return
                        threading.Thread(target=interrupt_read, daemon=True,
                                         name="ask-stream-deadline").start()
                        try:
                            yield from self._read_stream(resp, deadline, visibility, controls,
                                                         content_parts, calls, cancel_event)
                            finish_reason = resp.extensions.get("lifeos_finish_reason")
                        finally:
                            finished.set()
        except httpx.TimeoutException as exc:
            raise OpenClawTimeout(f"Local model timed out: {exc}") from exc
        except httpx.HTTPStatusError as exc:
            raise _translate_http_error(exc, "streaming request") from exc
        except httpx.HTTPError as exc:
            if monotonic() >= deadline:
                raise OpenClawTimeout("Local model exceeded its generation deadline") from exc
            raise OpenClawUnavailable(f"OpenClaw streaming request failed: {exc}") from exc

        if finish_reason == "length":
            raise OpenClawTruncated("Local model output reached its token limit")
        if finish_reason is None:
            raise OpenClawUnavailable("The model stream ended without a completion marker")

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

    @staticmethod
    def _read_stream(resp, deadline, visibility, controls, content_parts, calls, cancel_event):
        finish_reason = None
        for line in resp.iter_lines():
            if cancel_event and cancel_event.is_set():
                raise OpenClawUnavailable("Request cancelled")
            if monotonic() >= deadline:
                raise OpenClawTimeout("Local model exceeded its generation deadline")
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
        resp.extensions["lifeos_finish_reason"] = finish_reason

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

    def chat_json(
        self,
        messages: list[dict[str, str]],
        schema: dict,
        schema_name: str,
        max_tokens: int = 512,
        temperature: float = 0.0,
        timeout: float | None = None,
    ) -> dict:
        """Return a JSON object produced under server-side guided decoding.

        The Jetson's EdgeLLM build enforces ``response_format: json_schema``
        (strict) during decoding, so the reply always parses and matches the
        schema's types/enums. Callers still validate semantics (dates, ranges).
        """
        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": temperature,
            "stream": False,
            "max_tokens": max_tokens,
            "response_format": {
                "type": "json_schema",
                "json_schema": {"name": schema_name, "strict": True, "schema": schema},
            },
        }
        try:
            data = self._post_completion(payload, timeout, "structured request")
        except OpenClawEngineError:
            # The guided decoder occasionally loops on some inputs (observed:
            # emoji-heavy marketing mail, "JSON output was incomplete at the
            # token limit") although the same prompt answers in <100 tokens
            # unconstrained. Retry once unconstrained and validate locally.
            payload.pop("response_format")
            payload["messages"] = _inject_schema_instructions(messages, schema)
            data = self._post_completion(payload, timeout, "structured fallback request")
            parsed = _json_object(_completion_message(data).get("content") or "")
            if not _matches_json_schema(parsed, schema):
                raise OpenClawUnavailable("Local model reply did not match the schema")
            return parsed
        return _json_object(_completion_message(data).get("content") or "")

    def _post_completion(self, payload: dict, timeout: float | None, operation: str) -> dict:
        url = f"{self.base_url}/v1/chat/completions"
        try:
            with self._admitted(timeout) as request_timeout:
                with httpx.Client(timeout=request_timeout) as client:
                    resp = client.post(url, json=payload, headers=self._headers())
                    resp.raise_for_status()
                    return resp.json()
        except httpx.TimeoutException as exc:
            raise OpenClawTimeout(f"Local model timed out: {exc}") from exc
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code == 500 and "engine_error" in exc.response.text:
                raise OpenClawEngineError(
                    f"OpenClaw {operation} failed in the engine: {exc.response.text[:300]}"
                ) from exc
            raise _translate_http_error(exc, operation) from exc
        except (httpx.HTTPError, ValueError) as exc:
            raise OpenClawUnavailable(f"OpenClaw request failed: {exc}") from exc

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
