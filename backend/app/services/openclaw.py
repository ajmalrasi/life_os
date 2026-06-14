"""OpenClaw client.

Talks to a locally hosted OpenClaw server (OpenAI-compatible chat completions)
running on the Jetson Orin Nano. Base URL, model, and key are all configurable.

If the server is unreachable we raise ``OpenClawUnavailable`` so the API layer
can degrade gracefully instead of fabricating an answer.
"""

from __future__ import annotations

import httpx

from app.config import get_settings

settings = get_settings()


class OpenClawUnavailable(RuntimeError):
    pass


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

    def chat(self, messages: list[dict[str, str]], temperature: float = 0.2) -> str:
        """Call /v1/chat/completions and return the assistant text."""
        url = f"{self.base_url}/v1/chat/completions"
        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": temperature,
            "stream": False,
        }
        try:
            with httpx.Client(timeout=self.timeout) as client:
                resp = client.post(url, json=payload, headers=self._headers())
                resp.raise_for_status()
                data = resp.json()
        except (httpx.HTTPError, ValueError) as exc:  # network error or bad JSON
            raise OpenClawUnavailable(f"OpenClaw request failed: {exc}") from exc

        try:
            return data["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            raise OpenClawUnavailable(f"Unexpected OpenClaw response shape: {data}") from exc

    def chat_with_tools(
        self,
        messages: list[dict],
        tools: list[dict],
        temperature: float = 0.2,
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
        }
        try:
            with httpx.Client(timeout=self.timeout) as client:
                resp = client.post(url, json=payload, headers=self._headers())
                resp.raise_for_status()
                data = resp.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise OpenClawUnavailable(f"OpenClaw request failed: {exc}") from exc

        try:
            return data["choices"][0]["message"]
        except (KeyError, IndexError, TypeError) as exc:
            raise OpenClawUnavailable(f"Unexpected OpenClaw response shape: {data}") from exc

    def health(self) -> bool:
        try:
            with httpx.Client(timeout=5.0) as client:
                resp = client.get(f"{self.base_url}/v1/models", headers=self._headers())
                return resp.status_code < 500
        except httpx.HTTPError:
            return False
