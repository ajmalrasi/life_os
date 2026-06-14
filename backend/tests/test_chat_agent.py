"""Tests for the OpenClaw agent loop and its data tools (Phase 1 #6)."""

from __future__ import annotations

from datetime import date, datetime, timedelta

from app.services import agent_tools
from app.services.agent_tools import AgentTools
from app.services.chat_service import MAX_AGENT_STEPS, ChatService


class _ToolThenAnswerClient:
    """Requests one tool call, then returns a final answer."""

    def __init__(self):
        self.calls = 0

    def chat_with_tools(self, messages, tools, temperature=0.2):
        self.calls += 1
        if self.calls == 1:
            return {
                "role": "assistant",
                "tool_calls": [
                    {"id": "t1", "function": {"name": "health_summary", "arguments": '{"days": 7}'}}
                ],
            }
        return {"role": "assistant", "content": "final answer (avg sleep 78)"}

    def chat(self, messages, temperature=0.2):
        return "should-not-be-called"


class _AlwaysToolsClient:
    def chat_with_tools(self, messages, tools, temperature=0.2):
        return {
            "role": "assistant",
            "tool_calls": [{"id": "x", "function": {"name": "git_log", "arguments": "{}"}}],
        }

    def chat(self, messages, temperature=0.2):
        return "capped-summary"


def test_agent_executes_tool_then_answers(client, monkeypatch):
    executed = []

    class FakeTools:
        def __init__(self, db):
            pass

        def run(self, name, args):
            executed.append((name, args))
            return {"ok": True, "name": name}

    monkeypatch.setattr(agent_tools, "AgentTools", FakeTools)
    # chat_service imported the symbol directly, so patch it there too.
    import app.services.chat_service as cs

    monkeypatch.setattr(cs, "AgentTools", FakeTools)

    svc = ChatService(db=None, client=_ToolThenAnswerClient())
    answer = svc._run_agent([{"role": "user", "content": "why am I tired?"}])

    assert answer == "final answer (avg sleep 78)"
    assert executed == [("health_summary", {"days": 7})]


def test_agent_loop_is_bounded(client, monkeypatch):
    import app.services.chat_service as cs

    class FakeTools:
        def __init__(self, db):
            pass

        def run(self, name, args):
            return {"ok": True}

    monkeypatch.setattr(cs, "AgentTools", FakeTools)

    svc = ChatService(db=None, client=_AlwaysToolsClient())
    answer = svc._run_agent([{"role": "user", "content": "q"}])
    assert answer == "capped-summary"


def _seed_mood_and_exec(client, n_days=10):
    today = date.today()
    for i in range(n_days):
        day = today - timedelta(days=i)
        client.post(
            "/github",
            json={"day": day.isoformat(), "commits": 3, "coding_minutes": 90, "additions": 50, "deletions": 10},
        )
        client.post(
            "/mood",
            json={"day": day.isoformat(), "mood": 4, "energy": 4, "focus": 3, "stress": 2},
        )
    client.post("/analytics/recompute?days=14")
    return today


def test_agent_tools_read_real_data(client):
    """AgentTools should return grounded data from the seeded DB."""
    _seed_mood_and_exec(client)

    from app.database import SessionLocal

    db = SessionLocal()
    try:
        tools = AgentTools(db)

        git = tools.run("git_log", {"days": 14})
        assert git["totals"]["commits"] >= 30
        assert git["totals"]["active_days"] >= 10

        gh = tools.run("github_events", {"days": 14})
        assert gh["totals"]["additions"] >= 500

        health = tools.run("health_summary", {"days": 14})
        assert health["averages"]["wellness_score"] is not None
        assert health["coverage"]["days_with_pulse"] >= 10

        # Unknown tool degrades, never raises.
        err = tools.run("nope", {})
        assert "error" in err
    finally:
        db.close()
