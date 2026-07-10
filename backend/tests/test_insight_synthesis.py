"""LLM-synthesized cross-domain insights.

The synthesizer's whole safety property is grounding: it may only build on the
evidence facts it was handed, and every insight must cite at least one real fact
id. These tests pin that guard and the best-effort fallbacks.
"""

from __future__ import annotations

import pytest

from app.services import insight_synthesizer
from app.services.insight_synthesizer import EvidenceFact, synthesize
from app.services.openclaw import OpenClawUnavailable


class _FakeClient:
    def __init__(self, response: str = "", fail: bool = False) -> None:
        self.response = response
        self.fail = fail
        self.timeout = 120.0
        self.calls = 0

    def chat(self, messages, temperature=0.2) -> str:
        self.calls += 1
        if self.fail:
            raise OpenClawUnavailable("down")
        return self.response


def _evidence() -> list[EvidenceFact]:
    return [
        EvidenceFact("corr:stress->daily_spend", "Daily spend rises after high-stress days (r=+0.5, n=9)."),
        EvidenceFact("trend:recovery_score", "Recovery is trending down: 55 vs 68 the week before."),
        EvidenceFact("avg:daily_spend", "Daily spend averaged 1800 over 30 days."),
    ]


def test_synthesize_keeps_grounded_insights():
    client = _FakeClient(
        '[{"title": "Stress fuels spend", "insight": "Heavy-spend days follow stress spikes '
        'while recovery slides.", "theme": "spending", '
        '"cites": ["corr:stress->daily_spend", "trend:recovery_score"]}]'
    )
    out = synthesize(_evidence(), client=client)
    assert len(out) == 1
    assert out[0]["title"] == "Stress fuels spend"
    assert out[0]["cites"] == ["corr:stress->daily_spend", "trend:recovery_score"]
    assert out[0]["theme"] == "spending"


def test_synthesize_drops_insight_citing_only_unknown_ids():
    """An insight grounded in nothing we handed the model is an invented
    relationship — it must be dropped, not stored."""
    client = _FakeClient(
        '[{"title": "Made up", "insight": "Sleep drives spending.", '
        '"cites": ["corr:sleep_score->daily_spend"]}]'  # id never supplied
    )
    assert synthesize(_evidence(), client=client) == []


def test_synthesize_strips_unknown_cites_but_keeps_valid_one():
    client = _FakeClient(
        '[{"title": "Mixed", "insight": "Stress and spend move together.", '
        '"cites": ["corr:stress->daily_spend", "corr:made_up->thing"]}]'
    )
    out = synthesize(_evidence(), client=client)
    assert out and out[0]["cites"] == ["corr:stress->daily_spend"]


def test_synthesize_requires_cites():
    client = _FakeClient('[{"title": "No cites", "insight": "A vibe.", "cites": []}]')
    assert synthesize(_evidence(), client=client) == []


def test_synthesize_caps_to_four():
    items = ",".join(
        '{"title": "t%d", "insight": "i", "cites": ["avg:daily_spend"]}' % i for i in range(8)
    )
    client = _FakeClient(f"[{items}]")
    assert len(synthesize(_evidence(), client=client)) == 4


def test_synthesize_empty_when_unavailable():
    assert synthesize(_evidence(), client=_FakeClient(fail=True)) == []


def test_synthesize_empty_on_junk():
    assert synthesize(_evidence(), client=_FakeClient("not json")) == []


def test_synthesize_no_evidence_short_circuits():
    client = _FakeClient('[{"title": "x", "insight": "y", "cites": ["z"]}]')
    assert synthesize([], client=client) == []
    assert client.calls == 0


def test_synthesize_skips_when_disabled(monkeypatch):
    monkeypatch.setattr(insight_synthesizer.settings, "insight_synthesis_enabled", False)
    client = _FakeClient('[{"title": "x", "insight": "y", "cites": ["avg:daily_spend"]}]')
    assert synthesize(_evidence(), client=client) == []
    assert client.calls == 0


def test_synthesis_repo_replace_and_endpoint(client, monkeypatch):
    """Discovery stores synthesized insights and the endpoint returns them."""
    from datetime import date, timedelta
    from decimal import Decimal

    from app.database import SessionLocal
    from app.models.mood import MoodCheckin
    from app.models.transaction import Transaction
    import app.services.analytics as analytics_mod
    from app.services.analytics import AnalyticsService

    # Deterministic stubs for both LLM passes.
    monkeypatch.setattr(analytics_mod.insight_narrator, "narrate", lambda facts, client=None: {})
    monkeypatch.setattr(
        analytics_mod.insight_synthesizer,
        "synthesize",
        lambda evidence, client=None: [
            {"title": "Stress spend", "body": "You spend more after stress.",
             "theme": "spending", "cites": [evidence[0].id]}
        ] if evidence else [],
    )

    start = date(2026, 6, 20)
    days = [start + timedelta(days=i) for i in range(10)]
    with SessionLocal() as db:
        for i, day in enumerate(days):
            stress = 1 + (i % 5)
            db.add(MoodCheckin(day=day, mood=3, energy=3, focus=3, stress=stress))
            db.add(Transaction(
                txn_date=day, amount=Decimal(f"{stress * 500}.00"), direction="debit",
                source="upi", gmail_message_id=f"s{i}", dedup_key=f"s{i}",
            ))
        db.commit()
        AnalyticsService(db).discover(days[0], days[-1])

    resp = client.get("/analytics/synthesis")
    assert resp.status_code == 200
    body = resp.json()
    assert body and body[0]["title"] == "Stress spend"
    assert body[0]["cites"]  # grounded citation round-tripped
