"""Shared FastAPI dependencies."""

from __future__ import annotations

import hmac

from fastapi import Header, HTTPException, status

from app.config import get_settings


def require_token(authorization: str | None = Header(default=None)) -> None:
    """Bearer-token auth gate for routers that opt in via
    ``include_router(..., dependencies=[Depends(require_token)])``.

    ``Settings.api_token`` unset (the default) disables auth entirely — this
    keeps the existing home-Wi-Fi-only rollout and the test suite working
    without configuration. Once set (see AGENTS.md §2, ahead of exposing the
    API over Tailscale), every protected route requires an exact
    ``Authorization: Bearer <token>`` header, compared in constant time so a
    timing side-channel can't leak the token a byte at a time.
    """
    token = get_settings().api_token
    if not token:
        return

    if authorization is None or not authorization.startswith("Bearer "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )

    provided = authorization.removeprefix("Bearer ").strip()
    if not hmac.compare_digest(provided, token):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )
