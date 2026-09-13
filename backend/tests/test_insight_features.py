"""Tests for LLM-narrated insights and data-grounded chat suggestions.

Both features are best-effort layers over deterministic data: when OpenClaw is
unreachable or returns junk, the system must fall back cleanly (summaries for
insights) and never raise.
"""

from __future__ import annotations

import pytest

from app.services import insight_narrator
from app.services.insight_narrator import InsightFact, narrate
from app.services.openclaw import OpenClawUnavailable


class _FakeClient:
    """Returns a canned chat() response, or raises if `fail` is set."""

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


def _facts() -> list[InsightFact]:
    return [
        InsightFact("sleep_score", "execution_score", 0.62, 21, 0, "summary A"),
        InsightFact("fitness_score", "mood", 0.41, 18, 1, "summary B"),
    ]


# --- insight narration ---------------------------------------------------------


def test_narrate_maps_insights_to_pair_keys_by_id():
    client = _FakeClient(
        '[{"id": "sleep_score|execution_score", "insight": "You ship more on rested days."},'
        ' {"id": "fitness_score|mood", "insight": "Movement lifts your mood."}]'
    )
    out = narrate(_facts(), client=client)
    assert out == {
        "sleep_score|execution_score": "You ship more on rested days.",
        "fitness_score|mood": "Movement lifts your mood.",
    }


def test_narrate_matches_by_id_even_when_reordered():
    """A response in a different order than the facts must not misattribute —
    each narrative stays with its own pattern via the id, not by position."""
    client = _FakeClient(
        '[{"id": "fitness_score|mood", "insight": "mood one"},'
        ' {"id": "sleep_score|execution_score", "insight": "exec one"}]'
    )
    out = narrate(_facts(), client=client)
    assert out["sleep_score|execution_score"] == "exec one"
    assert out["fitness_score|mood"] == "mood one"


def test_narrate_drops_unknown_ids():
    """Narratives for ids we didn't send are ignored, never stapled onto a fact."""
    client = _FakeClient(
        '[{"id": "hallucinated|pair", "insight": "wrong"},'
        ' {"id": "sleep_score|execution_score", "insight": "right"}]'
    )
    out = narrate(_facts(), client=client)
    assert out == {"sleep_score|execution_score": "right"}


def test_narrate_tolerates_code_fences_and_prose():
    client = _FakeClient(
        '```json\n[{"id": "sleep_score|execution_score", "insight": "a narrative"},'
        ' {"id": "fitness_score|mood", "insight": "b narrative"}]\n```'
    )
    out = narrate(_facts(), client=client)
    assert out["sleep_score|execution_score"] == "a narrative"


def test_narrate_returns_empty_when_unavailable():
    assert narrate(_facts(), client=_FakeClient(fail=True)) == {}


def test_narrate_returns_empty_on_unparseable_response():
    assert narrate(_facts(), client=_FakeClient("not json at all")) == {}


def test_narrate_skips_when_disabled(monkeypatch):
    monkeypatch.setattr(insight_narrator.settings, "insight_narration_enabled", False)
    client = _FakeClient('["x", "y"]')
    assert narrate(_facts(), client=client) == {}
    assert client.calls == 0  # never even contacted the model


def test_narrate_no_facts_short_circuits():
    client = _FakeClient('["x"]')
    assert narrate([], client=client) == {}
    assert client.calls == 0
