"""Test fixtures.

We point DATABASE_URL at an on-disk SQLite file *before* importing the app so
the whole stack (models, engine, sessions) runs without Postgres. OpenClaw is
never contacted because tests use the deterministic fallback path or stub it.
"""

from __future__ import annotations

import os
import tempfile

import pytest

# Must be set before importing app.config / app.database.
_TMP_DB = os.path.join(tempfile.gettempdir(), "lifeos_test.db")
os.environ["DATABASE_URL"] = f"sqlite+pysqlite:///{_TMP_DB}"
os.environ["ENVIRONMENT"] = "test"

from fastapi.testclient import TestClient  # noqa: E402

from app.database import Base, engine  # noqa: E402
from app.main import app  # noqa: E402


@pytest.fixture()
def client():
    # Fresh schema per test for isolation.
    Base.metadata.drop_all(bind=engine)
    Base.metadata.create_all(bind=engine)
    with TestClient(app) as c:
        yield c
    Base.metadata.drop_all(bind=engine)
