"""Sync wrapper around the github-mcp-server binary (stdio transport).

The github-mcp-server binary is baked into the API Docker image (multi-stage
build from ghcr.io/github/github-mcp-server). We spawn it with ``stdio``
transport so auth is just the GITHUB_PERSONAL_ACCESS_TOKEN env var — no OAuth
server or sidecar required.

FastAPI runs sync route handlers in a thread pool (no running event loop), so
``asyncio.run()`` is safe. The ``_run`` helper falls back to a fresh thread
when called from an already-running loop (e.g. during tests with pytest-asyncio).
"""

from __future__ import annotations

import asyncio
import concurrent.futures
import json
import logging
import os
from typing import Any

logger = logging.getLogger(__name__)

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
    """Spawn github-mcp-server stdio and call tools synchronously."""

    def __init__(self, token: str) -> None:
        self.token = token

    # -- public sync API ---------------------------------------------------------

    def call_tool(self, tool_name: str, arguments: dict | None = None,
                  timeout: float = _TOOL_TIMEOUT) -> Any:
        """Call a tool and return the parsed JSON result, or None on error."""
        if not self.token:
            logger.debug("github-mcp: no token configured, skipping %s", tool_name)
            return None
        try:
            async def bounded():
                return await asyncio.wait_for(self._call_tool(tool_name, arguments or {}), timeout)
            return _run(bounded())
        except Exception as exc:
            logger.warning("github-mcp tool %s failed: %s", tool_name, exc)
            return None

    def is_available(self) -> bool:
        """Return True if the binary exists and the token is set."""
        import shutil
        return bool(self.token) and shutil.which("github-mcp-server") is not None

    # -- async internals ---------------------------------------------------------

    async def _call_tool(self, tool_name: str, arguments: dict) -> Any:
        from mcp import ClientSession, StdioServerParameters
        from mcp.client.stdio import stdio_client

        env = {**os.environ, "GITHUB_PERSONAL_ACCESS_TOKEN": self.token}
        params = StdioServerParameters(
            command="github-mcp-server",
            args=["stdio", "--read-only"],
            env=env,
        )

        async with stdio_client(params) as (read, write):
            async with ClientSession(read, write) as session:
                await session.initialize()
                result = await session.call_tool(tool_name, arguments)

        for block in result.content or []:
            text = getattr(block, "text", None)
            if text:
                try:
                    return json.loads(text)
                except (json.JSONDecodeError, TypeError):
                    return text
        return None
