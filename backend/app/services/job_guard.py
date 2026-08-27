"""Cross-request guards for singleton background jobs.

The local threading lock handles the current single-Uvicorn deployment. The
PostgreSQL advisory lock keeps the same guarantee if LifeOS later adds workers
or a second API container, without adding a schema or leaving stale lock rows
after a crash.
"""

from __future__ import annotations

import hashlib
import threading
from collections import defaultdict
from contextlib import contextmanager
from collections.abc import Iterator

from sqlalchemy import text
from sqlalchemy.engine import Engine


MAIL_SYNC_JOB = "lifeos:mail-sync"

_LOCKS_GUARD = threading.Lock()
_LOCAL_LOCKS: defaultdict[str, threading.Lock] = defaultdict(threading.Lock)


def _lock_key(name: str) -> int:
    digest = hashlib.blake2b(name.encode("utf-8"), digest_size=8).digest()
    return int.from_bytes(digest, byteorder="big", signed=True)


@contextmanager
def try_job_lock(engine: Engine, name: str) -> Iterator[bool]:
    """Yield whether the named job lease was acquired, never waiting for it."""

    with _LOCKS_GUARD:
        local_lock = _LOCAL_LOCKS[name]
    if not local_lock.acquire(blocking=False):
        yield False
        return

    try:
        if engine.dialect.name != "postgresql":
            yield True
            return

        key = _lock_key(name)
        # Session-level advisory locks must be acquired and released on the
        # exact same connection. Keep this dedicated connection checked out
        # across the job even though the request's ORM session commits.
        with engine.connect() as connection:
            acquired = bool(
                connection.execute(
                    text("SELECT pg_try_advisory_lock(:key)"), {"key": key}
                ).scalar()
            )
            if not acquired:
                yield False
                return
            try:
                yield True
            finally:
                connection.execute(text("SELECT pg_advisory_unlock(:key)"), {"key": key})
    finally:
        local_lock.release()
