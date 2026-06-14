"""Sync wrapper around the official github-mcp-server SSE sidecar.

The github-mcp-server (ghcr.io/github/github-mcp-server) runs alongside the
backend in docker-compose and exposes the GitHub API as MCP tools. This module
provides a blocking interface over the async MCP Python SDK so the rest of the
backend doesn't need to go async.

Because FastAPI runs sync route handlers in a thread pool (no running event
loop in those threads), ``asyncio.run()`` is safe here. If called from an async
context the fallback creates a new thread with its own loop.
"""

from __future__ import annotations

import asyncio
import concurrent.futures
import json
import logging
from typing import Any

logger = logging.getLogger(__name__)

_CONNECT_TIMEOUT = 10.0
_TOOL_TIMEOUT = 30.0


def _run(coro) -> Any:
    """Run a coroutine from a sync context, regardless of event-loop state."""
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        loop = None

    if loop and loop.is_running():
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            return pool.submit(asyncio.run, coro).result(timeout=_TOOL_TIMEOUT + 5)
    return asyncio.run(coro)


class GitHubMcpClient:
    """Call github-mcp-server tools synchronously via its SSE endpoint."""

    def __init__(self, server_url: str) -> None:
        self.server_url = server_url.rstrip("/")

    # -- public sync API ---------------------------------------------------------

    def call_tool(self, tool_name: str, arguments: dict | None = None) -> Any:
        """Call a tool and return the parsed JSON result, or None on error."""
        try:
            return _run(self._call_tool(tool_name, arguments or {}))
        except Exception as exc:
            logger.warning("github-mcp-server tool %s failed: %s", tool_name, exc)
            return None

    def is_available(self) -> bool:
        try:
            _run(self._ping())
            return True
        except Exception:
            return False

    # -- async internals ---------------------------------------------------------

    async def _ping(self) -> None:
        from mcp import ClientSession
        from mcp.client.sse import sse_client

        async with sse_client(f"{self.server_url}/sse") as (read, write):
            async with ClientSession(read, write) as session:
                await session.initialize()

    async def _call_tool(self, tool_name: str, arguments: dict) -> Any:
        from mcp import ClientSession
        from mcp.client.sse import sse_client

        async with sse_client(f"{self.server_url}/sse") as (read, write):
            async with ClientSession(read, write) as session:
                await session.initialize()
                result = await session.call_tool(tool_name, arguments)

        # MCP returns a list of ContentBlock; extract the first text block as JSON.
        for block in result.content or []:
            text = getattr(block, "text", None)
            if text:
                try:
                    return json.loads(text)
                except (json.JSONDecodeError, TypeError):
                    return text
        return None
