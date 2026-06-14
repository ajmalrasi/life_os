"""Tests for device-token registration and the fail-safe FCM sender (Phase 1 #5)."""

from __future__ import annotations

from app.services.fcm import FcmService


def test_register_device_token(client):
    res = client.post("/device/token", json={"token": "abc123", "platform": "android"})
    assert res.status_code == 200
    body = res.json()
    assert body["registered"] is True
    # FCM disabled by default (no FIREBASE_CREDENTIALS_PATH in test env).
    assert body["push_enabled"] is False


def test_register_is_idempotent(client):
    client.post("/device/token", json={"token": "dup"})
    client.post("/device/token", json={"token": "dup"})

    from app.database import SessionLocal
    from app.repositories.device_repo import DeviceRepository

    db = SessionLocal()
    try:
        tokens = DeviceRepository(db).all_tokens()
        assert tokens.count("dup") == 1
    finally:
        db.close()


def test_unregister_device_token(client):
    client.post("/device/token", json={"token": "todelete"})
    res = client.request("DELETE", "/device/token", json={"token": "todelete"})
    assert res.status_code == 200
    assert res.json()["registered"] is False


def test_fcm_disabled_is_noop():
    class _Note:
        severity = "warning"
        title = "t"
        detail = "d"

    result = FcmService().push_notifications([_Note()], ["tok"])
    assert result.enabled is False
    assert result.attempted == 0
