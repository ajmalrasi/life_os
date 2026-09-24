"""LLM-synthesized, cross-domain insights.

Where ``Insight`` is one row per statistically-discovered driver→outcome pair,
this table holds higher-level insights the LLM *synthesizes* by connecting
several grounded facts at once (e.g. "heavy-spend days cluster with low recovery
and dips in execution"). The synthesis is free-form narrative, but every one is
anchored to computed evidence via ``cites`` — the model may only build on facts
we handed it, so it can't invent a relationship that isn't in the data.

The whole set is regenerated each nightly run (see ``insight_synthesizer``), so
rows are transient and carry no natural key.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, Integer, String
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base, utcnow


class SynthesizedInsight(Base):
    __tablename__ = "synthesized_insights"

    id: Mapped[int] = mapped_column(primary_key=True)

    # Short headline and the insight itself.
    title: Mapped[str] = mapped_column(String(140))
    body: Mapped[str] = mapped_column(String(800))

    # Loose theme tag for grouping/filtering in the UI. Since the mentor
    # rewrite (Stage 3 of mentor-insights.md) this holds the insight's
    # "domain" (money|trip|health|mind|work|life).
    theme: Mapped[str | None] = mapped_column(String(48), nullable=True)

    # One concrete next step, e.g. "Cap eating out at ₹4,000 this month."
    # Nullable so older rows (and any model output that omits it) keep working.
    action: Mapped[str | None] = mapped_column(String(300), nullable=True)

    # "high" | "medium" | "low" — drives ranking and the UI's severity dot.
    severity: Mapped[str | None] = mapped_column(String(8), nullable=True)

    # Comma-joined ids of the evidence facts this insight is grounded in. Every
    # id here was present in the evidence bundle handed to the model, validated
    # at generation time — a claim citing nothing grounded is dropped, not stored.
    cites: Mapped[str] = mapped_column(String(600), default="")

    # Display order (0 = most prominent).
    rank: Mapped[int] = mapped_column(Integer, default=0)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
