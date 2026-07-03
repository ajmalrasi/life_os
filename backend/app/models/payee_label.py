"""User-managed payee → category labels.

The deterministic keyword categorizer in ``app/api/routes/statements.py`` only
recognises well-known merchant keywords, so P2P transfers, loan EMIs, remittances
and other person/entity payees fall into the dominant "other" bucket. This table
lets the user teach the categorizer about their own recurring payees (editable
from the Money tab) without a code change or redeploy.

One row per rule. ``pattern`` is matched against a transaction's ``counterparty``
according to ``match_type``; the first active rule to match (ordered by
``priority`` then ``id``) wins, and is consulted *before* the built-in keyword
rules so user intent always overrides the defaults.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import Boolean, DateTime, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base, utcnow

# Allowed matching strategies for ``PayeeLabel.match_type``.
MATCH_TYPES = ("substring", "exact", "regex")


class PayeeLabel(Base):
    __tablename__ = "payee_labels"
    __table_args__ = (
        UniqueConstraint("pattern", "match_type", name="uq_payee_label_pattern"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)

    # The text to look for in a transaction's counterparty. Interpreted per
    # ``match_type``; matching is always case-insensitive.
    pattern: Mapped[str] = mapped_column(String(256), index=True)
    category: Mapped[str] = mapped_column(String(64), index=True)
    match_type: Mapped[str] = mapped_column(String(16), default="substring")

    # Lower priority is evaluated first. Lets a specific rule win over a broader
    # one (e.g. an exact name before a loose substring).
    priority: Mapped[int] = mapped_column(Integer, default=100)
    active: Mapped[bool] = mapped_column(Boolean, default=True)

    # Optional human note, e.g. "lawyer fee — not lending".
    note: Mapped[str | None] = mapped_column(String(256), default=None)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow, onupdate=utcnow
    )
