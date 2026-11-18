"""Bearer-token auth gate (app.api.deps.require_token) — see AGENTS.md §2.

Settings.api_token is unset by default (including in the test suite's
environment, per conftest.py), so auth is disabled unless a test explicitly
opts in by setting it on the cached Settings instance.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.main import app


@pytest.mark.parametrize("token", [None, "", "   "])
def test_production_requires_a_nonblank_api_token_before_starting(monkeypatch, token):
    settings = get_settings()
    monkeypatch.setattr(settings, "environment", "production")
    monkeypatch.setattr(settings, "api_token", token)
    with pytest.raises(RuntimeError, match="API_TOKEN is required in production"):
        with TestClient(app):
            pass


def test_disabled_by_default_allows_protected_route_without_a_header(client):
    assert get_settings().api_token is None  # sanity: the default the suite relies on
    assert client.get("/notifications").status_code == 200


def test_exempt_paths_never_require_a_token(client, monkeypatch):
    monkeypatch.setattr(get_settings(), "api_token", "secret-token")

    assert client.get("/healthz").status_code == 200
    assert client.get("/").status_code == 200
    # Health Auto Export sends no headers; {} is a valid payload (all fields
    # default), so 200 (not 401) proves this route is exempt, not just lenient.
    assert client.post("/health/auto-export", json={}).status_code == 200


def test_protected_route_401s_without_a_token(client, monkeypatch):
    monkeypatch.setattr(get_settings(), "api_token", "secret-token")
    assert client.get("/notifications").status_code == 401


def test_protected_route_401s_with_the_wrong_token(client, monkeypatch):
    monkeypatch.setattr(get_settings(), "api_token", "secret-token")
    resp = client.get("/notifications", headers={"Authorization": "Bearer wrong-token"})
    assert resp.status_code == 401


def test_protected_route_401s_with_a_malformed_header(client, monkeypatch):
    monkeypatch.setattr(get_settings(), "api_token", "secret-token")
    resp = client.get("/notifications", headers={"Authorization": "secret-token"})  # no "Bearer "
    assert resp.status_code == 401


def test_protected_route_200s_with_the_right_token(client, monkeypatch):
    monkeypatch.setattr(get_settings(), "api_token", "secret-token")
    resp = client.get("/notifications", headers={"Authorization": "Bearer secret-token"})
    assert resp.status_code == 200


def test_health_batch_is_protected_even_though_auto_export_is_exempt(client, monkeypatch):
    monkeypatch.setattr(get_settings(), "api_token", "secret-token")

    without_token = client.post("/health/batch", json={"records": []})
    assert without_token.status_code == 401

    with_token = client.post(
        "/health/batch",
        json={"records": []},
        headers={"Authorization": "Bearer secret-token"},
    )
    assert with_token.status_code == 200
