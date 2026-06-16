"""Database engine, session factory, and declarative base."""

from __future__ import annotations

from collections.abc import Generator

from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from app.config import get_settings

settings = get_settings()

# SQLite (used by the test suite) needs check_same_thread disabled so the
# TestClient's worker threads can share connections. Postgres ignores this.
_connect_args = (
    {"check_same_thread": False} if settings.database_url.startswith("sqlite") else {}
)

engine = create_engine(
    settings.database_url,
    pool_pre_ping=True,
    future=True,
    connect_args=_connect_args,
)

SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False, future=True)


class Base(DeclarativeBase):
    """Declarative base for all ORM models."""


def get_db() -> Generator[Session, None, None]:
    """FastAPI dependency that yields a scoped session."""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def init_db() -> None:
    """Create all tables. For the MVP we use create_all; later phases add Alembic."""
    # Import models so they register with the metadata before create_all.
    from app import models  # noqa: F401

    Base.metadata.create_all(bind=engine)


def purge_seed_data() -> None:
    """Remove any synthetic 'seed-*' health records left by the old debug seeding
    button, then recompute the trailing window so derived scores no longer reflect
    sample data. Idempotent: a cheap no-op once the database is clean.
    """
    import logging
    from datetime import date, timedelta

    from sqlalchemy import delete

    from app.models.health import HealthRecord

    log = logging.getLogger("lifeos.purge_seed")
    with SessionLocal() as db:
        removed = db.execute(
            delete(HealthRecord).where(HealthRecord.source_id.like("seed-%"))
        ).rowcount
        db.commit()
        if not removed:
            return
        log.warning("Purged %d seeded health record(s); recomputing scores.", removed)
        # Seed window spanned ~30 days; recompute a generous trailing window so the
        # stored scores/states drop the sample sleep & recovery they were built on.
        from app.services.analytics import AnalyticsService

        end = date.today()
        start = end - timedelta(days=89)
        AnalyticsService(db).run_nightly(start, end)
        db.commit()
