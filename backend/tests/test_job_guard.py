"""Singleton job admission for Gmail-backed syncs."""

from app.database import engine
from app.services.job_guard import MAIL_SYNC_JOB, try_job_lock


def test_mail_sync_guard_defers_overlapping_job():
    with try_job_lock(engine, MAIL_SYNC_JOB) as first:
        assert first is True
        with try_job_lock(engine, MAIL_SYNC_JOB) as second:
            assert second is False

    with try_job_lock(engine, MAIL_SYNC_JOB) as after_release:
        assert after_release is True
