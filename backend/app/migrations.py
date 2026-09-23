"""Lightweight SQL migration runner (not Alembic).

``database.init_db()`` uses SQLAlchemy's ``create_all``, which creates missing
tables but never ALTERs an existing one (see AGENTS.md §5). Historically that
meant column/type changes needed a manual, undocumented ``ALTER TABLE`` run by
hand on the Jetson. This module replaces that with numbered, idempotent SQL
files under ``backend/migrations/`` that are applied automatically on startup,
tracked in a ``schema_migrations`` table so each file runs at most once.

This is deliberately not Alembic: no autogeneration, no down-migrations, no
branching. Files are plain SQL, applied once each, in filename order. Keep
each file idempotent (``IF NOT EXISTS`` / ``IF EXISTS``) anyway, so replaying
a file (e.g. after a manual ``schema_migrations`` edit) is harmless.

SQLite (used by the test suite) is skipped by default: the files are written
for Postgres syntax (``ADD COLUMN IF NOT EXISTS`` etc.) that SQLite doesn't
support. Pass ``force=True`` to run them anyway (for a test that wants to
exercise the runner itself against a throwaway SQLite db).
"""

from __future__ import annotations

import logging
from pathlib import Path

from sqlalchemy import text
from sqlalchemy.engine import Engine

logger = logging.getLogger(__name__)

MIGRATIONS_DIR = Path(__file__).resolve().parent.parent / "migrations"

_CREATE_TRACKING_TABLE = """
CREATE TABLE IF NOT EXISTS schema_migrations (
    name VARCHAR(255) PRIMARY KEY,
    applied_at TIMESTAMP NOT NULL
)
"""


def _migration_files() -> list[Path]:
    if not MIGRATIONS_DIR.is_dir():
        return []
    return sorted(MIGRATIONS_DIR.glob("*.sql"), key=lambda p: p.name)


def apply_migrations(engine: Engine, *, force: bool = False) -> list[str]:
    """Apply any unapplied migration files, in order. Returns the names applied.

    Each file runs inside its own transaction; a failure stops the run (later
    files are not applied) and re-raises so startup fails loudly rather than
    booting against a half-migrated schema.
    """
    if engine.dialect.name == "sqlite" and not force:
        logger.info("Skipping SQL migrations on sqlite (test/dev-only dialect)")
        return []

    files = _migration_files()
    if not files:
        return []

    applied: list[str] = []
    with engine.begin() as conn:
        conn.execute(text(_CREATE_TRACKING_TABLE))
        already = {
            row[0] for row in conn.execute(text("SELECT name FROM schema_migrations"))
        }

    for path in files:
        if path.name in already:
            continue
        sql = path.read_text()
        with engine.begin() as conn:
            for statement in _split_statements(sql):
                conn.execute(text(statement))
            conn.execute(
                text(
                    "INSERT INTO schema_migrations (name, applied_at) "
                    "VALUES (:name, CURRENT_TIMESTAMP)"
                ),
                {"name": path.name},
            )
        logger.info("Applied migration %s", path.name)
        applied.append(path.name)

    return applied


def _split_statements(sql: str) -> list[str]:
    """Split a migration file into individual statements.

    SQLAlchemy's raw ``text()`` execution does not support multiple
    semicolon-separated statements in one call via psycopg2's default mode,
    so migration files (which may contain several ``ALTER``/``CREATE``
    statements) are split on top-level semicolons. Comment-only lines and
    blank statements are dropped.
    """
    statements = []
    for chunk in sql.split(";"):
        lines = [
            line for line in chunk.splitlines() if line.strip() and not line.strip().startswith("--")
        ]
        statement = "\n".join(lines).strip()
        if statement:
            statements.append(statement)
    return statements
