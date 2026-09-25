"""Regression tests for model-led Ask and exact read-only tools."""
from datetime import date, timedelta
from time import monotonic
import json
import pytest

from app.database import SessionLocal
from app.services import agent_tools
from app.services.agent_tools import AgentTools
from app.services.chat_service import ChatService, bounded_result
from app.services.money_query import MoneyQueryError, query_money
from app.schemas.chat import ChatRequest, ChatMessage
from app.services.openclaw import (
    OpenClawTimeout, OpenClawUnavailable, OpenClawRequestRejected,
    _StreamingThinkFilter, _StreamingControlTokenFilter, _strip_control_tokens,
)

class Model:
    def __init__(self, rounds=None):
        self.rounds = rounds or ["Model answer."]
        self.calls = []
        self.counts = []

    def count_chat_tokens(self, messages, tools=None, timeout=5, **kwargs):
        self.counts.append(json.loads(json.dumps(messages)))
        return 1000

    def stream_chat(self, messages, **kwargs):
        self.calls.append((json.loads(json.dumps(messages)), kwargs))
        result = self.rounds[min(len(self.calls)-1, len(self.rounds)-1)]
        if isinstance(result, Exception):
            raise result
        if isinstance(result, str):
            yield {"type": "content", "text": result}
            result = {"role": "assistant", "content": result}
        yield {"type": "message", "message": result}

def tool(name="health_summary", arguments='{"days":7}'):
    return {"role": "assistant", "tool_calls": [
        {"id": "t1", "function": {"name": name, "arguments": arguments}}]}

@pytest.mark.parametrize("question", [
    "How was my sleep?", "How much did I spend today?", "Food last month?",
    "What should I focus on today?", "Write Fibonacci code",
])
def test_every_answer_comes_from_model_even_without_data(client, question):
    with SessionLocal() as db:
        model = Model()
        result = ChatService(db, model).ask(ChatRequest(message=question))
    assert result.answer == "Model answer."
    assert len(model.calls) == 1
    assert not result.grounded and not result.citations
    assert "DATA CONTEXT" not in str(model.calls[0][0])
    assert model.calls[0][1]["tools"]

def test_history_crosses_topics_and_followups_need_no_tool(client):
    model = Model()
    req = ChatRequest(message="Explain that code", history=[
        ChatMessage(role="user", content="How was my sleep?"),
        ChatMessage(role="assistant", content="Your sleep score was 70."),
        ChatMessage(role="user", content="Write Fibonacci code"),
        ChatMessage(role="assistant", content="def fib(n): ..."),
    ])
    with SessionLocal() as db:
        ChatService(db, model).ask(req)
    assert [m["content"] for m in model.calls[0][0][1:]] == [
        m.content for m in req.history] + [req.message]

def test_tools_supply_evidence_but_model_writes_answer(client):
    model = Model([tool("get_life_context"), "No recent records are available."])
    with SessionLocal() as db:
        response = ChatService(db, model).ask(ChatRequest(message="How am I doing?"))
    assert response.answer == "No recent records are available."
    assert response.grounded
    assert [c.metric for c in response.citations] == ["get_life_context"]
    result = json.loads(model.calls[1][0][-1]["content"])
    assert result["totals"]["days_with_scores"] == 0
    assert result["source_ref"] == response.citations[0].source_id

def test_invalid_tool_arguments_do_not_execute_default_query(client):
    model = Model([tool("query_money", "{bad"), "Please clarify."])
    with SessionLocal() as db:
        response = ChatService(db, model).ask(ChatRequest(message="My money"))
    assert not response.grounded
    assert "error" in json.loads(model.calls[1][0][-1]["content"])

def test_optional_tool_rounds_are_bounded(client):
    model = Model([tool(), tool("git_log"), "Done."])
    with SessionLocal() as db:
        result = ChatService(db, model).ask(ChatRequest(message="Compare my activity"))
    assert result.answer == "Done."
    assert len(model.calls) == 3
    assert model.calls[-1][1]["tools"] is None
    assert len(result.citations) == 2

def test_model_failure_is_error_not_canned_personal_answer(client):
    with SessionLocal() as db:
        result = ChatService(db, Model([OpenClawTimeout("test")])).ask(ChatRequest(message="My sleep"))
    assert result.answer == ""
    assert result.error and result.incomplete
    assert not result.grounded and not result.citations

def test_partial_response_is_preserved_on_failure(client):
    class Partial(Model):
        def stream_chat(self, messages, **kwargs):
            yield {"type": "content", "text": "Partial answer"}
            raise OpenClawTimeout("test")
    with SessionLocal() as db:
        result = ChatService(db, Partial()).ask(ChatRequest(message="Hi"))
    assert result.answer == "Partial answer" and result.incomplete

def test_token_budget_drops_complete_old_exchanges(client):
    class Counter(Model):
        def count_chat_tokens(self, messages, tools=None, timeout=5, **kwargs):
            return 6500 if len(messages) > 2 else 1000
    with SessionLocal() as db:
        service = ChatService(db, Counter())
        messages = service._messages(ChatRequest(message="current", history=[
            ChatMessage(role="user", content="old"),
            ChatMessage(role="assistant", content="old answer")]))
        fitted, output = service._fit(messages, [], monotonic()+10)
    assert len(fitted) == 2 and fitted[-1]["content"] == "current"
    assert output == 1024

def test_oversized_current_question_fails_without_cutting_it(client):
    model = Model()
    model.count_chat_tokens = lambda *a, **k: 7000
    with SessionLocal() as db:
        result = ChatService(db, model).ask(ChatRequest(message="long"))
    assert result.error and not model.calls

def test_bounding_keeps_totals_and_valid_json():
    result = bounded_result({"summary": {"total": 999}, "rows": [{"memo": "a"*900}]*50})
    assert result["summary"]["total"] == 999
    assert len(result["rows"]) == 20
    assert result["_truncated"]["rows"]["available"] == 50
    assert json.loads(json.dumps(result)) == result

def test_tool_deadline_is_enforced(client, monkeypatch):
    import time
    monkeypatch.setattr(AgentTools, "run", lambda *a: time.sleep(0.2))
    with SessionLocal() as db:
        with pytest.raises(OpenClawTimeout):
            ChatService(db)._tool("health_summary", {}, monotonic()-1)

def test_unavailable_live_tools_are_error_data(client):
    with SessionLocal() as db:
        assert AgentTools(db).run("live_health", {})["status"] == "error"
        assert AgentTools(db).run("github_search", {"tool": "delete_repository"})["status"] == "error"

def test_suggestions_cache_refresh_and_failure(client, monkeypatch):
    from app.services import chat_suggestions as module
    model = Model(['{"questions":["One?","Two?","Three?","Four?"]}'])
    model.admission_status = lambda: {"active_workload": None, "waiting_interactive": 0}
    monkeypatch.setattr(module, "OpenClawClient", lambda **kw: model)
    key = (30, module.datetime.now(module.IST).date().isoformat())
    module._cache.clear()
    module._refresh(SessionLocal.kw["bind"], key)
    assert module._cache[key][1] == ["One?", "Two?", "Three?", "Four?"]
    with SessionLocal() as db:
        assert module.suggestions(db, 30) == module._cache[key][1]
    model.rounds = [OpenClawTimeout("busy")]
    module._refresh(SessionLocal.kw["bind"], key)
    assert len(module._cache[key][1]) == 4
    module._cache.clear()

def test_cancelled_stream_stops_model_work(client):
    import threading
    import time
    closed = threading.Event()
    class Cancellable(Model):
        def stream_chat(self, messages, **kwargs):
            try:
                yield {"type": "content", "text": "First"}
                while not kwargs["cancel_event"].wait(0.01):
                    yield {"type": "content", "text": " more"}
            finally:
                closed.set()
    with SessionLocal() as db:
        stream = ChatService(db, Cancellable()).stream(ChatRequest(message="hello"))
        for event in stream:
            if event["type"] == "delta":
                break
        stream.close()
        assert closed.wait(1)

def test_absolute_deadline_ends_a_stalled_model(client, monkeypatch):
    import time
    from app.services import chat_service
    class Stalled(Model):
        def stream_chat(self, messages, **kwargs):
            kwargs["cancel_event"].wait(3)
            yield {"type": "content", "text": "too late"}
    monkeypatch.setattr(chat_service.settings, "openclaw_chat_deadline_seconds", 1.1)
    started = monotonic()
    with SessionLocal() as db:
        response = ChatService(db, Stalled()).ask(ChatRequest(message="hello"))
    assert monotonic()-started < 1.6
    assert response.incomplete and "timed out" in response.error

def test_suggestions_defer_to_interactive_work(client, monkeypatch):
    from app.services import chat_suggestions as module
    model = Model()
    model.admission_status = lambda: {"active_workload": "interactive", "waiting_interactive": 1}
    monkeypatch.setattr(module, "OpenClawClient", lambda **kw: model)
    key = (7, module.datetime.now(module.IST).date().isoformat())
    module._refresh(SessionLocal.kw["bind"], key)
    assert not model.calls and key not in module._cache

def test_suggestions_invalid_json_keeps_empty_state(client, monkeypatch):
    from app.services import chat_suggestions as module
    model = Model(["not JSON"])
    model.admission_status = lambda: {"active_workload": None, "waiting_interactive": 0}
    monkeypatch.setattr(module, "OpenClawClient", lambda **kw: model)
    key = (8, module.datetime.now(module.IST).date().isoformat())
    module._refresh(SessionLocal.kw["bind"], key)
    assert key not in module._cache

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
        with pytest.raises(MoneyQueryError, match="Both comparison"):
            query_money(db, compare_start_date="2026-01-01")

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


def test_money_comparison_computes_exact_changes(client):
    from decimal import Decimal
    from app.models.transaction import Transaction
    with SessionLocal() as db:
        for day, amount, key in [(date(2026, 8, 1), "100", "before"),
                                 (date(2026, 9, 1), "450", "after")]:
            db.add(Transaction(txn_date=day, amount=Decimal(amount), currency="INR",
                               direction="debit", source="upi", counterparty="ZOMATO",
                               gmail_message_id=key, dedup_key=key))
        db.commit()
        result = query_money(db, start_date="2026-09-01", end_date="2026-09-13",
                             compare_start_date="2026-08-01", compare_end_date="2026-08-31",
                             group_by="category")
        assert result["comparison"]["change"] == {"difference": 350, "percent_change": 350}
        assert result["comparison"]["groups"][0]["percent_change"] == 350
        empty = query_money(db, start_date="2026-09-01", end_date="2026-09-13",
                            compare_start_date="2026-07-01", compare_end_date="2026-07-31")
        assert empty["comparison"]["change"]["percent_change"] is None


def test_health_records_reads_synced_data_and_separates_walks(client):
    from datetime import date, datetime, timedelta
    from app.repositories.health_repo import HealthRepository
    from app.schemas.health import HealthRecordIn

    today = date.today()
    at = lambda d, h: datetime.combine(d, datetime.min.time()).replace(hour=h)
    with SessionLocal() as db:
        repo = HealthRepository(db)
        repo.bulk_insert([
            HealthRecordIn(source_id="hae-s", type="steps", value=4200, unit="count",
                           start_time=at(today, 9), end_time=at(today, 9)),
            HealthRecordIn(source_id="hae-w1", type="workout", value=50, unit="min",
                           start_time=at(today - timedelta(days=3), 21), end_time=at(today - timedelta(days=3), 22),
                           label="Traditional Strength Training"),
            HealthRecordIn(source_id="hae-w2", type="workout", value=19, unit="min",
                           start_time=at(today - timedelta(days=1), 18), end_time=at(today - timedelta(days=1), 19),
                           label="Outdoor Walk"),
        ])
        result = AgentTools(db).run("health_records", {"days": 7})
    assert result["daily"][0]["day"] == today.isoformat() and result["daily"][0]["steps"] == 4200
    assert [(w["type"], w["kind"]) for w in result["workouts"]] == [
        ("Outdoor Walk", "walking"), ("Traditional Strength Training", "training"),
    ]
    assert result["window_totals"]["training_workouts"] == 1
    assert result["window_totals"]["walking_workouts"] == 1
    assert result["last_training_workout"]["type"] == "Traditional Strength Training"
    assert "not set up" in result["freshness"]["workouts_sync"]


def test_health_records_counts_an_exact_calendar_month(client):
    from datetime import date, datetime
    from app.repositories.health_repo import HealthRepository
    from app.schemas.health import HealthRecordIn

    def lift(sid, d, minutes):
        start = datetime.combine(d, datetime.min.time()).replace(hour=21)
        return HealthRecordIn(source_id=sid, type="workout", value=minutes, unit="min",
                              start_time=start, end_time=start, label="Traditional Strength Training")

    year = date.today().year - 1  # a complete past month, well inside any window
    with SessionLocal() as db:
        HealthRepository(db).bulk_insert([
            lift("hae-a", date(year, 6, 29), 61), lift("hae-b", date(year, 7, 1), 91),
            lift("hae-c", date(year, 7, 29), 50), lift("hae-d", date(year, 8, 2), 30),
        ])
        result = AgentTools(db).run("health_records", {"start_date": f"{year}-07-01", "end_date": f"{year}-07-31"})
    assert result["window"]["start"] == f"{year}-07-01" and result["window"]["end"] == f"{year}-07-31"
    assert result["window_totals"] == {
        "training_workouts": 2, "training_minutes": 141.0, "walking_workouts": 0, "training_days": 2,
    }
    wide = AgentTools(SessionLocal()).health_records(90, f"{year}-06-01", f"{year}-08-31")
    assert wide["by_month"][f"{year}-07"] == {
        "training_workouts": 2, "training_minutes": 141.0, "walking_workouts": 0, "training_days": 2,
    }
    assert wide["by_month"][f"{year}-06"]["training_workouts"] == 1
