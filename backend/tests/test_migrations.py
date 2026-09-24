"""Tests for app.migrations: the numbered-SQL-file runner (see AGENTS.md §5)."""

from __future__ import annotations

from sqlalchemy import create_engine, text

from app import migrations


def _sqlite_engine(tmp_path):
    return create_engine(f"sqlite+pysqlite:///{tmp_path}/migrations_test.db", future=True)


def test_skips_sqlite_by_default(tmp_path, monkeypatch):
    monkeypatch.setattr(migrations, "MIGRATIONS_DIR", tmp_path)
    (tmp_path / "0001_a.sql").write_text("CREATE TABLE IF NOT EXISTS a (id INTEGER);")

    engine = _sqlite_engine(tmp_path)
    applied = migrations.apply_migrations(engine)

    assert applied == []
    with engine.connect() as conn:
        tables = conn.execute(
            text("SELECT name FROM sqlite_master WHERE type='table'")
        ).scalars().all()
    assert "schema_migrations" not in tables
    assert "a" not in tables


def test_no_migration_files_is_a_noop(tmp_path, monkeypatch):
    monkeypatch.setattr(migrations, "MIGRATIONS_DIR", tmp_path)
    engine = _sqlite_engine(tmp_path)

    assert migrations.apply_migrations(engine, force=True) == []


def test_applies_each_file_once_in_order(tmp_path, monkeypatch):
    migrations_dir = tmp_path / "migrations"
    migrations_dir.mkdir()
    monkeypatch.setattr(migrations, "MIGRATIONS_DIR", migrations_dir)

    # Two statements in one file, to exercise the semicolon split.
    (migrations_dir / "0001_a.sql").write_text(
        "-- comment line, should be ignored\n"
        "CREATE TABLE IF NOT EXISTS a (id INTEGER);\n"
        "CREATE TABLE IF NOT EXISTS a2 (id INTEGER);\n"
    )
    (migrations_dir / "0002_b.sql").write_text("CREATE TABLE IF NOT EXISTS b (id INTEGER);")

    engine = _sqlite_engine(tmp_path)

    first_run = migrations.apply_migrations(engine, force=True)
    assert first_run == ["0001_a.sql", "0002_b.sql"]

    with engine.connect() as conn:
        tables = set(
            conn.execute(text("SELECT name FROM sqlite_master WHERE type='table'")).scalars()
        )
        applied_names = set(
            conn.execute(text("SELECT name FROM schema_migrations")).scalars()
        )
    assert {"a", "a2", "b", "schema_migrations"} <= tables
    assert applied_names == {"0001_a.sql", "0002_b.sql"}

    # Re-running must not re-apply (idempotent at the file-tracking level, even
    # for a migration whose own SQL wasn't itself IF-NOT-EXISTS-safe).
    second_run = migrations.apply_migrations(engine, force=True)
    assert second_run == []


def test_applying_a_new_file_alongside_already_applied_ones(tmp_path, monkeypatch):
    migrations_dir = tmp_path / "migrations"
    migrations_dir.mkdir()
    monkeypatch.setattr(migrations, "MIGRATIONS_DIR", migrations_dir)
    (migrations_dir / "0001_a.sql").write_text("CREATE TABLE IF NOT EXISTS a (id INTEGER);")

    engine = _sqlite_engine(tmp_path)
    assert migrations.apply_migrations(engine, force=True) == ["0001_a.sql"]

    (migrations_dir / "0002_b.sql").write_text("CREATE TABLE IF NOT EXISTS b (id INTEGER);")
    assert migrations.apply_migrations(engine, force=True) == ["0002_b.sql"]


def test_semicolon_inside_comment_does_not_split_statement():
    from app.migrations import _split_statements

    sql = "-- note; with a semicolon\n-- more\nALTER TABLE t ADD COLUMN a INT;\nALTER TABLE t ADD COLUMN b INT;\n"
    assert _split_statements(sql) == [
        "ALTER TABLE t ADD COLUMN a INT",
        "ALTER TABLE t ADD COLUMN b INT",
    ]
