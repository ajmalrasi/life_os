"""Client for the Health Auto Export TCP server running on the iPhone.

The iOS app exposes a JSON-RPC-over-TCP server on the LAN (Automations -> Server
-> Start Server, port 9000). This lets LifeOS pull *live, on-demand* Apple Health
data — e.g. when the user asks "how did I sleep last night?" in the chat — for
data that may not have been pushed to the backend yet.

Protocol (docs: health-auto-export-mcp-server/docs/tcp-server.md):
  - TCP, one request per connection; the server replies with one JSON object and
    closes the socket. No TLS/auth — LAN only.
  - Request:  {"jsonrpc":"2.0","id":..,"method":"callTool",
               "params":{"name":"health_metrics","metrics":"heart_rate,...",
                         "arguments":{"start":"yyyy-MM-dd HH:mm:ss Z","end":..,
                                      "interval":"days","aggregate":true}}}
  - Response: the same {"data":{"metrics":[...]}} shape the REST export uses
    (possibly wrapped in a JSON-RPC {"result": ...}).

It is foreground-only: if the app is backgrounded or the phone is locked the
socket refuses — callers must degrade gracefully and tell the user to open the
app and start the server.
"""
from __future__ import annotations

import json
import logging
import socket
from datetime import datetime
from typing import Any

logger = logging.getLogger(__name__)

# Health Auto Export's required date format: "yyyy-MM-dd HH:mm:ss Z".
HAE_DATE_FMT = "%Y-%m-%d %H:%M:%S %z"


def format_hae_date(dt: datetime) -> str:
    if dt.tzinfo is None:
        dt = dt.astimezone()
    return dt.strftime(HAE_DATE_FMT)


class PhoneHealthUnavailable(Exception):
    """Raised when the phone's TCP server can't be reached or returns garbage."""


class PhoneHealthClient:
    def __init__(self, host: str, port: int = 9000, timeout: float = 8.0) -> None:
        self.host = host
        self.port = port
        self.timeout = timeout

    @property
    def configured(self) -> bool:
        return bool(self.host)

    def call_tool(self, name: str, arguments: dict[str, Any],
                  metrics: str = "") -> dict[str, Any]:
        """Send one callTool request and return the parsed response dict.

        Raises PhoneHealthUnavailable on any connection/parse failure so the
        caller can return a friendly "start the server" message.
        """
        if not self.configured:
            raise PhoneHealthUnavailable("phone health server host not configured")

        # The `metrics` filter must live *inside* arguments — the server ignores
        # it when placed alongside `name`.
        args = dict(arguments)
        if metrics:
            args["metrics"] = metrics
        params: dict[str, Any] = {"name": name, "arguments": args}
        request = {"jsonrpc": "2.0", "id": "lifeos", "method": "callTool", "params": params}
        payload = json.dumps(request).encode("utf-8")

        try:
            with socket.create_connection((self.host, self.port), timeout=self.timeout) as sock:
                sock.settimeout(self.timeout)
                sock.sendall(payload)
                chunks: list[bytes] = []
                while True:  # server closes the socket when the response is complete
                    buf = sock.recv(8192)
                    if not buf:
                        break
                    chunks.append(buf)
        except OSError as exc:
            raise PhoneHealthUnavailable(
                f"cannot reach Health Auto Export server at {self.host}:{self.port} ({exc})"
            ) from exc

        raw = b"".join(chunks).decode("utf-8", errors="replace").strip()
        if not raw:
            raise PhoneHealthUnavailable("empty response from phone health server")
        try:
            return json.loads(raw)
        except json.JSONDecodeError as exc:
            raise PhoneHealthUnavailable(f"invalid JSON from phone health server: {exc}") from exc


def extract_metrics(response: dict[str, Any]) -> list[dict[str, Any]]:
    """Pull the metrics array out of a response regardless of JSON-RPC wrapping."""
    node = response
    if isinstance(node.get("result"), dict):
        node = node["result"]
    data = node.get("data")
    if isinstance(data, dict) and isinstance(data.get("metrics"), list):
        return data["metrics"]
    if isinstance(node.get("metrics"), list):
        return node["metrics"]
    return []
