"""Computes per-source sync health for GET /status/sync (Stage 3).

Two different notions of "freshness" are both surfaced, deliberately:
  - *data* freshness — when a source last actually produced something new
    (a bank alert, a statement, a sleep record). A sync that keeps
    "succeeding" while finding nothing new for days is still worth flagging.
  - *attempt* freshness — whether the sync mechanism itself is even running,
    and if not, why (e.g. a Gmail token stuck on ``invalid_grant``, which
    would otherwise look identical to "quietly fine" from data alone).

Each source's ``stale`` bit considers whichever of these applies to it.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timezone

from sqlalchemy import func
from sqlalchemy.orm import Session

from app.config import get_settings
from app.database import utcnow
from app.models.card_statement import CardStatement
from app.models.health import HealthRecord
from app.models.mail import MailSyncState
from app.models.sync_state import CollectorState
from app.repositories.sync_status_repo import SyncAttemptRepository
from app.services.openclaw import OpenClawClient
from app.services.sync_sources import SOURCE_BANK_MAIL, SOURCE_STATEMENTS

SOURCE_PRIORITY_MAIL = "priority_mail"
SOURCE_SLEEP_VITALS = "sleep_vitals"
SOURCE_MODEL = "model"


@dataclass
class SourceStatus:
    source: str
    last_success_at: datetime | None
    last_attempt_at: datetime | None
    last_error: str | None
    stale: bool


def _hours_since(dt: datetime | None) -> float | None:
    if dt is None:
        return None
    # SQLite (the test suite) doesn't round-trip tzinfo even on a
    # DateTime(timezone=True) column, so a naive value here is read back
    # exactly as it was written — by app.database.utcnow() — i.e. it's UTC.
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return (utcnow() - dt).total_seconds() / 3600.0


def _days_since(d: date | None) -> float | None:
    if d is None:
        return None
    return (date.today() - d).days


def _attempt_failed(row) -> bool:
    """True when the most recent attempt did not succeed (an error is set and
    no later success has cleared it)."""
    if row is None or not row.last_error:
        return False
    if row.last_success_at is None:
        return True
    return bool(row.last_attempt_at and row.last_attempt_at > row.last_success_at)


def _bank_mail_status(db: Session, attempts: SyncAttemptRepository, stale_hours: int) -> SourceStatus:
    row = attempts.get(SOURCE_BANK_MAIL)
    # CollectorState only advances when a sync actually sees a *new* email
    # (see ExpenseCollector.sync), so its freshest key is a true data signal,
    # not just "the last HTTP call returned 200".
    newest_cursor_update = (
        db.query(func.max(CollectorState.updated_at))
        .filter(CollectorState.key.like("gmail_expenses:%"))
        .scalar()
    )
    age = _hours_since(newest_cursor_update)
    return SourceStatus(
        source=SOURCE_BANK_MAIL,
        last_success_at=newest_cursor_update,
        last_attempt_at=row.last_attempt_at if row else None,
        last_error=row.last_error if row else None,
        stale=_attempt_failed(row) or age is None or age > stale_hours,
    )


def _statements_status(db: Session, attempts: SyncAttemptRepository, stale_days: int) -> SourceStatus:
    row = attempts.get(SOURCE_STATEMENTS)
    newest_statement_date = db.query(func.max(CardStatement.statement_date)).scalar()
    age_days = _days_since(newest_statement_date)
    newest_at = (
        datetime.combine(newest_statement_date, datetime.min.time()).replace(
            tzinfo=utcnow().tzinfo
        )
        if newest_statement_date
        else None
    )
    return SourceStatus(
        source=SOURCE_STATEMENTS,
        last_success_at=newest_at,
        last_attempt_at=row.last_attempt_at if row else None,
        last_error=row.last_error if row else None,
        stale=_attempt_failed(row) or age_days is None or age_days > stale_days,
    )


def _priority_mail_status(db: Session, stale_hours: int) -> SourceStatus:
    rows = db.query(MailSyncState).filter(MailSyncState.consumer == "priority").all()
    last_success = max((r.last_success_at for r in rows if r.last_success_at), default=None)
    newest_attempt_row = max(
        (r for r in rows if r.last_attempt_at), key=lambda r: r.last_attempt_at, default=None
    )
    age = _hours_since(last_success)
    return SourceStatus(
        source=SOURCE_PRIORITY_MAIL,
        last_success_at=last_success,
        last_attempt_at=newest_attempt_row.last_attempt_at if newest_attempt_row else None,
        last_error=newest_attempt_row.last_error if newest_attempt_row else None,
        stale=_attempt_failed(newest_attempt_row) or age is None or age > stale_hours,
    )


def _sleep_vitals_status(db: Session, stale_hours: int) -> SourceStatus:
    newest = (
        db.query(func.max(HealthRecord.created_at))
        .filter(HealthRecord.source_id.like("hae-%"))
        .scalar()
    )
    age = _hours_since(newest)
    return SourceStatus(
        source=SOURCE_SLEEP_VITALS,
        last_success_at=newest,
        last_attempt_at=newest,
        last_error=None,
        stale=age is None or age > stale_hours,
    )


def _model_status() -> SourceStatus:
    reachable = OpenClawClient().health()
    now = utcnow()
    return SourceStatus(
        source=SOURCE_MODEL,
        last_success_at=now if reachable else None,
        last_attempt_at=now,
        last_error=None if reachable else "OpenClaw /v1/models unreachable",
        stale=not reachable,
    )


def compute_sync_status(db: Session) -> list[SourceStatus]:
    settings = get_settings()
    attempts = SyncAttemptRepository(db)
    return [
        _bank_mail_status(db, attempts, settings.status_bank_mail_stale_hours),
        _statements_status(db, attempts, settings.status_statements_stale_days),
        _priority_mail_status(db, settings.status_priority_mail_stale_hours),
        _sleep_vitals_status(db, settings.status_sleep_stale_hours),
        _model_status(),
    ]
