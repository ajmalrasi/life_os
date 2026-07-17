"""Tests for the OpenClaw agent loop and its data tools (Phase 1 #6)."""

from __future__ import annotations

from datetime import date, datetime, timedelta

import pytest

from app.services import agent_tools
from app.services.agent_tools import AgentTools
from app.services.chat_service import MAX_AGENT_STEPS, ChatService, _is_personal_data_question
from app.services.openclaw import OpenClawTimeout, _StreamingThinkFilter


class _ToolThenAnswerClient:
    """Requests one tool call, then returns a final answer."""

    def __init__(self):
        self.calls = 0
        self.timeout = 90.0

    def chat_with_tools(self, messages, tools, temperature=0.2, timeout=None):
        self.calls += 1
        if self.calls == 1:
            return {
                "role": "assistant",
                "tool_calls": [
                    {"id": "t1", "function": {"name": "health_summary", "arguments": '{"days": 7}'}}
                ],
            }
        return {"role": "assistant", "content": "final answer (avg sleep 78)"}

    def chat(self, messages, temperature=0.2, timeout=None):
        return "should-not-be-called"


class _AlwaysToolsClient:
    timeout = 90.0

    def chat_with_tools(self, messages, tools, temperature=0.2, timeout=None):
        return {
            "role": "assistant",
            "tool_calls": [{"id": "x", "function": {"name": "git_log", "arguments": "{}"}}],
        }

    def chat(self, messages, temperature=0.2, timeout=None):
        return "capped-summary"


class _TimeoutClient:
    timeout = 90.0

    def chat_with_tools(self, messages, tools, temperature=0.2, timeout=None):
        raise OpenClawTimeout("read timed out")


class _GeneralClient:
    timeout = 90.0

    def __init__(self):
        self.messages = []

    def chat(self, messages, temperature=0.2, timeout=None):
        self.messages = messages
        return "def fibonacci(n):\n    return n if n < 2 else fibonacci(n - 1) + fibonacci(n - 2)"


class _StreamingGeneralClient(_GeneralClient):
    def stream_chat(self, messages, tools=None, temperature=0.2, timeout=None):
        self.messages = messages
        yield {"type": "content", "text": "def fibonacci"}
        yield {"type": "content", "text": "(n): return n"}
        yield {
            "type": "message",
            "message": {"role": "assistant", "content": "def fibonacci(n): return n", "tool_calls": []},
        }


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


def test_ask_reports_timeout_instead_of_claiming_openclaw_is_down(client):
    from app.schemas.chat import ChatRequest

    svc = ChatService(db=None, client=_TimeoutClient())
    svc.build_context = lambda days: {  # type: ignore[method-assign]
        "window": {"start": "2026-07-01", "end": "2026-07-16", "days": days},
        "averages": {"sleep_score": 72.0},
        "current_state": None,
        "totals": {"days_with_scores": 1, "days_with_mood_checkin": 0},
        "discovered_patterns": [],
    }

    response = svc.ask(ChatRequest(message="How am I doing?", context_days=30))
    assert response.answer.startswith("The local model timed out")
    assert "OpenClaw is unreachable" not in response.answer


def test_agent_enforces_whole_request_deadline(client, monkeypatch):
    import app.services.chat_service as cs

    ticks = iter((0.0, cs.settings.openclaw_chat_deadline_seconds + 2.0))
    monkeypatch.setattr(cs, "monotonic", lambda: next(ticks))
    svc = ChatService(db=None, client=_AlwaysToolsClient())

    with pytest.raises(OpenClawTimeout, match="overall time budget"):
        svc._run_agent([{"role": "user", "content": "q"}])


def test_general_code_question_does_not_receive_lifeos_context(client):
    from app.schemas.chat import ChatMessage, ChatRequest

    model = _GeneralClient()
    svc = ChatService(db=None, client=model)
    svc.build_context = lambda days: pytest.fail("general questions must not build personal context")

    response = svc.ask(
        ChatRequest(
            message="Write Python code for Fibonacci numbers",
            history=[ChatMessage(role="assistant", content="Your sleep score was low.")],
        )
    )

    assert "def fibonacci" in response.answer
    assert response.grounded is False
    assert response.citations == []
    assert "DATA CONTEXT" not in str(model.messages)
    assert "sleep score was low" not in str(model.messages)


def test_general_code_question_streams_without_personal_context(client):
    from app.schemas.chat import ChatMessage, ChatRequest

    model = _StreamingGeneralClient()
    svc = ChatService(db=None, client=model)
    svc.build_context = lambda days: pytest.fail("general streams must not build personal context")
    events = list(
        svc.stream(
            ChatRequest(
                message="Write Fibonacci code",
                history=[ChatMessage(role="assistant", content="Your sleep was low")],
            )
        )
    )

    assert [event["text"] for event in events if event["type"] == "delta"] == [
        "def fibonacci",
        "(n): return n",
    ]
    assert events[-1] == {"type": "done", "citations": [], "grounded": False}
    assert "sleep was low" not in str(model.messages)


def test_streaming_think_filter_hides_reasoning_without_buffering_normal_text():
    normal = _StreamingThinkFilter()
    assert normal.push("Hello") == "Hello"
    assert normal.push(" world") == " world"

    thinking = _StreamingThinkFilter()
    assert thinking.push("<thi") == ""
    assert thinking.push("nk>private reasoning") == ""
    assert thinking.push("</think>Final") == "Final"


@pytest.mark.parametrize(
    ("question", "personal"),
    [
        ("How was my sleep last night?", True),
        ("How am I doing this week?", True),
        ("How much did I spend this month?", True),
        ("Why am I unproductive?", True),
        ("What should I do today?", True),
        ("What is REM sleep?", False),
        ("Write Fibonacci code", False),
    ],
)
def test_chat_route_detection(question, personal):
    assert _is_personal_data_question(question) is personal


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
