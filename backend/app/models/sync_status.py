"""Tracks attempted (not just successful) syncs per source.

Complements the per-mailbox cursors in ``collector_state``/``mail_sync_states``:
those only advance on success, so a source that's failing every run (e.g. a
Gmail token stuck on ``invalid_grant``) looks identical to one that's simply
quiet. This table records every attempt, so ``last_attempt_at`` can be newer
than ``last_success_at`` and ``last_error`` explains why — the basis for
``GET /status/sync`` (see AGENTS.md §2/§7, Stage 3).
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, String
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


class SyncAttempt(Base):
    __tablename__ = "sync_attempts"

    source: Mapped[str] = mapped_column(String(64), primary_key=True)
    last_attempt_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    last_success_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    last_error: Mapped[str | None] = mapped_column(String(500), nullable=True)
