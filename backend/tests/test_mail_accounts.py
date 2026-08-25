from __future__ import annotations

import hashlib

from app.database import SessionLocal
from app.mail.oauth import GogOAuthService, RemoteAuthorization
from app.models.mail import MailAuthSession
from app.repositories.mail_repo import MailAccountRepository


def test_existing_authorization_assigns_money_without_new_consent(client, monkeypatch):
    monkeypatch.setattr(
        GogOAuthService,
        "authorized_accounts",
        lambda _self: {"money@example.com"},
    )
    response = client.post(
        "/mail/accounts/connect/start",
        json={
            "purpose": "finance",
            "email": "Money@Example.com",
            "display_label": "Money inbox",
            "callback_port": 53123,
        },
    )
    assert response.status_code == 200
    assert response.json()["requires_authorization"] is False
    assert response.json()["account"]["purposes"] == ["finance"]
    assert response.json()["account"]["authorized"] is True


def test_remote_oauth_session_pairs_callback_and_registers_priority(client, monkeypatch):
    monkeypatch.setattr(GogOAuthService, "authorized_accounts", lambda _self: set())
    monkeypatch.setattr(
        GogOAuthService,
        "start",
        lambda _self, _email, _redirect: RemoteAuthorization(
            "https://accounts.google.com/o/oauth2/v2/auth?state=test"
        ),
    )
    monkeypatch.setattr(
        GogOAuthService,
        "complete",
        lambda _self, email, _redirect, _callback: email,
    )

    started = client.post(
        "/mail/accounts/connect/start",
        json={
            "purpose": "priority",
            "email": "career@example.com",
            "display_label": "Priority inbox",
            "callback_port": 53124,
            "force_reauthorize": True,
        },
    )
    assert started.status_code == 200
    payload = started.json()
    assert payload["requires_authorization"] is True
    assert payload["session_secret"] not in payload["authorization_url"]

    with SessionLocal() as db:
        session = db.get(MailAuthSession, payload["session_id"])
        assert session.secret_hash == hashlib.sha256(
            payload["session_secret"].encode("utf-8")
        ).hexdigest()
        assert payload["session_secret"] != session.secret_hash

    completed = client.post(
        "/mail/accounts/connect/complete",
        json={
            "session_id": payload["session_id"],
            "session_secret": payload["session_secret"],
            "callback_url": (
                "http://127.0.0.1:53124/oauth2/callback?code=once&state=checked"
            ),
        },
    )
    assert completed.status_code == 200
    assert completed.json()["address"] == "career@example.com"
    assert completed.json()["purposes"] == ["priority"]

    replay = client.post(
        "/mail/accounts/connect/complete",
        json={
            "session_id": payload["session_id"],
            "session_secret": payload["session_secret"],
            "callback_url": (
                "http://127.0.0.1:53124/oauth2/callback?code=once&state=checked"
            ),
        },
    )
    assert replay.status_code == 404


def test_callback_must_return_to_the_exact_phone_loopback(client, monkeypatch):
    monkeypatch.setattr(GogOAuthService, "authorized_accounts", lambda _self: set())
    monkeypatch.setattr(
        GogOAuthService,
        "start",
        lambda _self, _email, _redirect: RemoteAuthorization(
            "https://accounts.google.com/o/oauth2/v2/auth?state=test"
        ),
    )
    started = client.post(
        "/mail/accounts/connect/start",
        json={
            "purpose": "priority",
            "email": "career@example.com",
            "callback_port": 53125,
        },
    ).json()
    response = client.post(
        "/mail/accounts/connect/complete",
        json={
            "session_id": started["session_id"],
            "session_secret": started["session_secret"],
            "callback_url": "http://attacker.test/oauth2/callback?code=x&state=y",
        },
    )
    assert response.status_code == 400
    assert "invalid callback" in response.json()["detail"]


def test_changing_purpose_is_exclusive_and_disconnect_is_not_legacy_restored(client):
    with SessionLocal() as db:
        repo = MailAccountRepository(db)
        old = repo.assign_exclusive("old@example.com", "finance", "Old")
        new = repo.assign_exclusive("new@example.com", "finance", "New")
        assert [row.id for row in repo.for_purpose("finance")] == [new.id]
        assert repo.disable_purpose(new.id, "finance") is True
        assert repo.finance_account("old@example.com") is None
        old_id = old.id

    response = client.delete(f"/mail/accounts/{old_id}/purposes/finance")
    assert response.status_code == 404
