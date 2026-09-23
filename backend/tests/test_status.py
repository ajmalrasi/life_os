"""GET /status/sync and the stale-source data behind it (Stage 3)."""

from __future__ import annotations

from datetime import date, timedelta

from app.database import SessionLocal, utcnow
from app.models.card_statement import CardStatement
from app.models.health import HealthRecord
from app.models.mail import MailAccount, MailSyncState
from app.models.sync_state import CollectorState
from app.repositories.sync_status_repo import SyncAttemptRepository
from app.services.sync_sources import SOURCE_BANK_MAIL, SOURCE_STATEMENTS


def _seed_fresh(db):
    now = utcnow()
    db.add(CollectorState(key="gmail_expenses:money@example.com", cursor_epoch=1, updated_at=now))
    db.add(
        CardStatement(
            bank="hdfc", card_label="HDFC Diners", card_last4="1234",
            statement_date=date.today() - timedelta(days=5), gmail_message_id="msg-fresh",
        )
    )
    db.add(
        HealthRecord(
            source_id="hae-abc123", type="sleep_duration", value=7.0, unit="h",
            start_time=now, end_time=now, created_at=now,
        )
    )
    account = MailAccount(provider="gmail", provider_account_id="priority@example.com", display_label="Priority")
    db.add(account)
    db.flush()
    db.add(MailSyncState(mail_account_id=account.id, consumer="priority", last_attempt_at=now, last_success_at=now))
    SyncAttemptRepository(db).record_success(SOURCE_BANK_MAIL)
    SyncAttemptRepository(db).record_success(SOURCE_STATEMENTS)
    db.commit()


def _seed_stale(db):
    old = utcnow() - timedelta(days=90)
    old_date = date.today() - timedelta(days=90)
    db.add(CollectorState(key="gmail_expenses:money@example.com", cursor_epoch=1, updated_at=old))
    db.add(
        CardStatement(
            bank="hdfc", card_label="HDFC Diners", card_last4="1234",
            statement_date=old_date, gmail_message_id="msg-old",
        )
    )
    account = MailAccount(provider="gmail", provider_account_id="priority@example.com", display_label="Priority")
    db.add(account)
    db.flush()
    db.add(MailSyncState(mail_account_id=account.id, consumer="priority", last_attempt_at=old, last_success_at=old))
    SyncAttemptRepository(db).record_success(SOURCE_BANK_MAIL)
    SyncAttemptRepository(db).record_success(SOURCE_STATEMENTS)
    # Force the attempts rows' timestamps into the past (record_success stamps "now").
    for source in (SOURCE_BANK_MAIL, SOURCE_STATEMENTS):
        row = SyncAttemptRepository(db).get(source)
        row.last_attempt_at = row.last_success_at = old
    db.commit()
    # sleep_vitals stays empty (no hae- rows) -> stale by construction.


def _status_by_source(payload):
    return {s["source"]: s for s in payload["sources"]}


def test_all_sources_present_with_expected_shape(client):
    resp = client.get("/status/sync")
    assert resp.status_code == 200
    payload = resp.json()
    sources = _status_by_source(payload)
    assert {"bank_mail", "statements", "priority_mail", "sleep_vitals", "model"} == set(sources)
    for status in sources.values():
        assert set(status) == {"source", "last_success_at", "last_attempt_at", "last_error", "stale"}


def test_fresh_data_sources_are_not_stale(client):
    with SessionLocal() as db:
        _seed_fresh(db)

    resp = client.get("/status/sync")
    sources = _status_by_source(resp.json())
    assert sources["bank_mail"]["stale"] is False
    assert sources["statements"]["stale"] is False
    assert sources["priority_mail"]["stale"] is False
    assert sources["sleep_vitals"]["stale"] is False


def test_old_data_sources_are_stale(client):
    with SessionLocal() as db:
        _seed_stale(db)

    resp = client.get("/status/sync")
    sources = _status_by_source(resp.json())
    assert sources["bank_mail"]["stale"] is True
    assert sources["statements"]["stale"] is True
    assert sources["priority_mail"]["stale"] is True
    assert sources["sleep_vitals"]["stale"] is True  # no hae- rows at all


def test_model_unreachable_in_test_environment_is_reported_stale(client):
    # conftest.py points OPENCLAW_BASE_URL at 127.0.0.1:1, so .health() always
    # fails fast here — a real assertion about the "unreachable" path, not a
    # mock standing in for one.
    resp = client.get("/status/sync")
    sources = _status_by_source(resp.json())
    assert sources["model"]["stale"] is True
    assert sources["model"]["last_error"]


def test_failed_attempt_marks_bank_mail_stale_even_with_recent_data(client):
    with SessionLocal() as db:
        _seed_fresh(db)  # fresh CollectorState data
        SyncAttemptRepository(db).record_error(SOURCE_BANK_MAIL, "invalid_grant")

    resp = client.get("/status/sync")
    sources = _status_by_source(resp.json())
    assert sources["bank_mail"]["stale"] is True
    assert sources["bank_mail"]["last_error"] == "invalid_grant"


def test_stale_sources_appear_in_notifications(client):
    with SessionLocal() as db:
        _seed_stale(db)

    resp = client.get("/notifications")
    assert resp.status_code == 200
    titles = [n["title"] for n in resp.json()]
    assert any("hasn't updated recently" in t for t in titles)
