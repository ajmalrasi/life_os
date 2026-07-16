"""OpenClaw client.

Talks to a locally hosted OpenClaw server (OpenAI-compatible chat completions)
running on the Jetson Orin Nano. Base URL, model, and key are all configurable.

If the server is unreachable we raise ``OpenClawUnavailable`` so the API layer
can degrade gracefully instead of fabricating an answer.
"""

from __future__ import annotations

import json

import httpx

from app.config import get_settings

settings = get_settings()

_PY_TYPES = {"string": str, "number": (int, float), "integer": int, "boolean": bool}


class OpenClawUnavailable(RuntimeError):
    pass


class OpenClawTimeout(OpenClawUnavailable):
    """The local model was reachable but did not finish in time."""

    pass


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
        return text
    return text[end + len("</think>") :].strip()


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
    def __init__(self, base_url: str | None = None, model: str | None = None) -> None:
        self.base_url = (base_url or settings.openclaw_base_url).rstrip("/")
        self.model = model or settings.openclaw_model
        self.timeout = settings.openclaw_timeout_seconds

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
            with httpx.Client(timeout=timeout or self.timeout) as client:
                resp = client.post(url, json=payload, headers=self._headers())
                resp.raise_for_status()
                data = resp.json()
        except httpx.TimeoutException as exc:
            raise OpenClawTimeout(f"Local model timed out: {exc}") from exc
        except (httpx.HTTPError, ValueError) as exc:  # network error or bad JSON
            raise OpenClawUnavailable(f"OpenClaw request failed: {exc}") from exc

        try:
            return _strip_think(data["choices"][0]["message"]["content"])
        except (KeyError, IndexError, TypeError) as exc:
            raise OpenClawUnavailable(f"Unexpected OpenClaw response shape: {data}") from exc

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
            with httpx.Client(timeout=timeout or self.timeout) as client:
                resp = client.post(url, json=payload, headers=self._headers())
                resp.raise_for_status()
                data = resp.json()
        except httpx.TimeoutException as exc:
            raise OpenClawTimeout(f"Local model timed out: {exc}") from exc
        except (httpx.HTTPError, ValueError) as exc:
            raise OpenClawUnavailable(f"OpenClaw request failed: {exc}") from exc

        try:
            message = data["choices"][0]["message"]
        except (KeyError, IndexError, TypeError) as exc:
            raise OpenClawUnavailable(f"Unexpected OpenClaw response shape: {data}") from exc
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
