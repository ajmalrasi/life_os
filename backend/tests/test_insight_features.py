"""Tests for LLM-narrated insights and data-grounded chat suggestions.

Both features are best-effort layers over deterministic data: when OpenClaw is
unreachable or returns junk, the system must fall back cleanly (summaries for
insights, static questions for suggestions) and never raise.
"""

from __future__ import annotations

import pytest

from app.services import insight_narrator
from app.services.insight_narrator import InsightFact, narrate
from app.services.chat_service import DEFAULT_SUGGESTIONS, ChatService
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


def test_narrate_maps_parsed_lines_to_pair_keys():
    client = _FakeClient('["You ship more on rested days.", "Movement lifts your mood."]')
    out = narrate(_facts(), client=client)
    assert out == {
        "sleep_score|execution_score": "You ship more on rested days.",
        "fitness_score|mood": "Movement lifts your mood.",
    }


def test_narrate_tolerates_code_fences_and_prose():
    client = _FakeClient('```json\n["a narrative", "b narrative"]\n```')
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


# --- chat suggestions ----------------------------------------------------------


def _svc(client: _FakeClient, has_data: bool) -> ChatService:
    svc = ChatService(db=None, client=client)
    # Avoid hitting the DB: feed a controlled context snapshot.
    svc.build_context = lambda days: {  # type: ignore[method-assign]
        "totals": {
            "days_with_scores": 9 if has_data else 0,
            "days_with_mood_checkin": 0,
        },
        "averages": {"sleep_score": 78},
        "current_state": None,
        "discovered_patterns": [],
    }
    return svc


def test_suggest_returns_defaults_without_data():
    client = _FakeClient('["should not be used"]')
    assert _svc(client, has_data=False).suggest() == DEFAULT_SUGGESTIONS
    assert client.calls == 0


def test_suggest_uses_model_questions_when_available():
    client = _FakeClient('["Is my sleep helping output?", "Am I recovering enough?"]')
    out = _svc(client, has_data=True).suggest()
    assert out == ["Is my sleep helping output?", "Am I recovering enough?"]


def test_suggest_falls_back_when_model_unavailable():
    assert _svc(_FakeClient(fail=True), has_data=True).suggest() == DEFAULT_SUGGESTIONS


def test_suggest_falls_back_on_junk_response():
    assert _svc(_FakeClient("sorry I can't"), has_data=True).suggest() == DEFAULT_SUGGESTIONS
