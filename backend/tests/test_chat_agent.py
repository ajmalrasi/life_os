"""Tests for the OpenClaw agent loop and its data tools (Phase 1 #6)."""

from __future__ import annotations

from datetime import date, datetime, timedelta

import pytest

from app.services import agent_tools
from app.services.agent_tools import AgentTools
from app.services.chat_service import MAX_AGENT_STEPS, ChatService, _is_personal_data_question
from app.services.money_query import MoneyQueryError, query_money
from app.services.openclaw import (
    OpenClawTimeout,
    _StreamingControlTokenFilter,
    _StreamingThinkFilter,
    _strip_control_tokens,
)


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
            history=[
                ChatMessage(role="user", content="How was my sleep last night?"),
                ChatMessage(role="assistant", content="Your sleep score was low."),
            ],
        )
    )

    assert "def fibonacci" in response.answer
    assert response.grounded is False
    assert response.citations == []
    assert "DATA CONTEXT" not in str(model.messages)
    assert "sleep score was low" not in str(model.messages)


def test_general_follow_up_receives_recent_conversation(client):
    from app.schemas.chat import ChatMessage, ChatRequest

    model = _GeneralClient()
    svc = ChatService(db=None, client=model)
    svc.build_context = lambda days: pytest.fail("general questions must not build personal context")

    svc.ask(
        ChatRequest(
            message="Now make it iterative and add type hints",
            history=[
                ChatMessage(role="user", content="Write a recursive Fibonacci function"),
                ChatMessage(role="assistant", content="def fibonacci(n): return n if n < 2 else fibonacci(n-1) + fibonacci(n-2)"),
            ],
        )
    )

    prompt = str(model.messages)
    assert "Write a recursive Fibonacci function" in prompt
    assert "def fibonacci(n)" in prompt
    assert "Now make it iterative" in prompt


def test_general_code_question_streams_without_personal_context(client):
    from app.schemas.chat import ChatMessage, ChatRequest

    model = _StreamingGeneralClient()
    svc = ChatService(db=None, client=model)
    svc.build_context = lambda days: pytest.fail("general streams must not build personal context")
    events = list(
        svc.stream(
            ChatRequest(
                message="Write Fibonacci code",
                history=[
                    ChatMessage(role="user", content="How was my sleep last night?"),
                    ChatMessage(role="assistant", content="Your sleep was low"),
                ],
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


def test_control_tokens_are_removed_from_complete_and_split_stream_text():
    assert _strip_control_tokens("<|im_start|>user Answer") == "Answer"
    assert _strip_control_tokens("<lim_start|>assistant Answer") == "Answer"

    stream = _StreamingControlTokenFilter()
    pieces = [stream.push("<|im_"), stream.push("start|>user Here is your answer"), stream.finish()]
    answer = "".join(pieces)
    assert answer == "Here is your answer"
    assert "im_start" not in answer


def test_spending_only_context_counts_as_personal_data():
    context = {
        "totals": {"days_with_scores": 0, "days_with_mood_checkin": 0, "commits": 0},
        "spending": {"total_spend": 654},
    }
    assert ChatService._context_has_data(context) is True


def test_historical_money_coverage_keeps_table_questions_available(client):
    from decimal import Decimal

    from app.database import SessionLocal
    from app.models.transaction import Transaction

    db = SessionLocal()
    try:
        db.add(
            Transaction(
                txn_date=date.today() - timedelta(days=120),
                amount=Decimal("750.00"),
                currency="INR",
                direction="debit",
                counterparty="OLD PURCHASE",
                account="HDFC Savings ..7672",
                source="upi",
                gmail_message_id="old",
                dedup_key="old",
            )
        )
        db.commit()

        context = ChatService(db).build_context(30)
        assert context["spending"] is None
        assert context["money_table_coverage"]["transaction_count"] == 1
        assert ChatService._context_has_data(context) is True
    finally:
        db.close()


def test_timeout_fallback_includes_calendar_month_spending():
    context = {
        "window": {"days": 30},
        "averages": {},
        "current_state": None,
        "spending": {
            "current_month": "2026-07",
            "current_month_total": 38712,
            "total_spend": 42100,
            "days_with_spend": 14,
        },
        "discovered_patterns": [],
    }
    answer = ChatService._fallback(None, context, "The local model timed out")
    assert "spending this calendar month (2026-07): ₹38,712" in answer
    assert "last 30 days: ₹42,100" in answer


def test_direct_personal_answers_cover_spending_and_sleep_without_model():
    context = {
        "window": {"start": "2026-07-01", "end": "2026-07-18", "days": 30},
        "averages": {"sleep_score": 44.8},
        "spending": {
            "current_month": "2026-07",
            "current_month_total": 38712,
            "total_spend": 76086,
            "days_with_spend": 29,
            "recent_daily_spend": [
                {"day": "2026-07-17", "spend": 654},
                {"day": "2026-07-18", "spend": 120},
            ],
        },
        "recent_daily": [
            {"day": "2026-07-17", "sleep": 51.2, "recovery": 62, "execution": 20}
        ],
    }
    assert ChatService._direct_personal_answer(
        "How much did I spend this month?", context
    ) == "You spent ₹38,712 this calendar month (2026-07)."
    assert ChatService._direct_personal_answer(
        "How much did I spend yesterday?", context
    ) == "You spent ₹654 yesterday (2026-07-17)."
    assert ChatService._direct_personal_answer("How is my sleep?", context) == (
        "Your latest sleep score is 51 on 2026-07-17; your 30-day average is 44.8."
    )
    # Qualified/table questions must reach query_money, never the current-month
    # total shortcut.
    assert ChatService._direct_personal_answer(
        "How much did I spend on food last month?", context
    ) is None
    assert ChatService._direct_personal_answer(
        "How much did I spend in the last 7 days?", context
    ) is None


@pytest.mark.parametrize(
    ("question", "personal"),
    [
        ("How was my sleep last night?", True),
        ("How am I doing this week?", True),
        ("How much did I spend this month?", True),
        ("What were my top merchants last month?", True),
        ("How much income did I receive this year?", True),
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


def test_query_money_groups_effective_spending_and_excludes_transfers(client):
    from decimal import Decimal

    from app.database import SessionLocal
    from app.models.transaction import Transaction

    db = SessionLocal()
    try:
        rows = [
            Transaction(
                txn_date=date(2026, 7, 2),
                amount=Decimal("500.00"),
                currency="INR",
                direction="debit",
                counterparty="ZOMATO",
                account="HDFC Savings ..7672",
                source="upi",
                gmail_message_id="food",
                dedup_key="food",
            ),
            Transaction(
                txn_date=date(2026, 7, 3),
                amount=Decimal("1200.00"),
                currency="INR",
                direction="debit",
                counterparty="AMAZON",
                spend_category_override="groceries",
                account="HDFC Card ..1234",
                source="card_live",
                gmail_message_id="groceries",
                dedup_key="groceries",
            ),
            Transaction(
                txn_date=date(2026, 7, 4),
                amount=Decimal("9000.00"),
                currency="INR",
                direction="debit",
                counterparty="SELF TRANSFER",
                category="transfer",
                account="HDFC Savings ..7672",
                source="upi",
                gmail_message_id="transfer",
                dedup_key="transfer",
            ),
            Transaction(
                txn_date=date(2026, 7, 5),
                amount=Decimal("2500.00"),
                currency="INR",
                direction="credit",
                counterparty="REFUND",
                account="HDFC Savings ..7672",
                source="bank_live",
                gmail_message_id="credit",
                dedup_key="credit",
            ),
        ]
        db.add_all(rows)
        db.commit()

        result = query_money(
            db,
            mode="spending",
            start_date="2026-07-01",
            end_date="2026-07-31",
            group_by="category",
            sort_by="total",
        )

        assert result["summary"] == {
            "total": 1700,
            "count": 2,
            "average": 850,
            "max": 1200,
        }
        assert result["matched_transactions"] == 2
        assert result["rows"] == [
            {
                "group": "groceries",
                "total": 1200,
                "count": 1,
                "average": 1200,
                "max": 1200,
            },
            {
                "group": "food",
                "total": 500,
                "count": 1,
                "average": 500,
                "max": 500,
            },
        ]

        food = AgentTools(db).run(
            "query_money",
            {
                "start_date": "2026-07-01",
                "end_date": "2026-07-31",
                "category": "food",
                "group_by": "none",
            },
        )
        assert food["summary"]["total"] == 500
        assert food["rows"][0]["counterparty"] == "ZOMATO"
        assert food["rows"][0]["category"] == "food"
    finally:
        db.close()


def test_query_money_rejects_invalid_contract_values(client):
    from app.database import SessionLocal

    db = SessionLocal()
    try:
        with pytest.raises(MoneyQueryError, match="group_by"):
            query_money(db, group_by="raw_sql")
        with pytest.raises(MoneyQueryError, match="start_date"):
            query_money(db, start_date="drop table transactions")
        with pytest.raises(MoneyQueryError, match="limit"):
            query_money(db, limit=5000)

        # Tool dispatch degrades invalid model arguments to an error payload.
        result = AgentTools(db).run("query_money", {"group_by": "raw_sql"})
        assert "error" in result
    finally:
        db.close()


def test_query_money_is_advertised_to_the_model():
    schemas = {schema["function"]["name"]: schema for schema in agent_tools.TOOL_SCHEMAS}
    assert "query_money" in schemas
    params = schemas["query_money"]["function"]["parameters"]["properties"]
    assert set(params["group_by"]["enum"]) >= {"month", "category", "counterparty", "account"}
