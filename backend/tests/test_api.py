"""Integration tests exercising the full ingest -> recompute -> query flow."""

from __future__ import annotations

from datetime import date, datetime, timedelta


def _iso(d: date, hour: int = 0) -> str:
    return datetime.combine(d, datetime.min.time()).replace(hour=hour).isoformat()


def _seed(client, n_days: int = 20):
    """Create a correlated history: more sleep -> more execution."""
    today = date.today()
    health_records = []
    for i in range(n_days):
        day = today - timedelta(days=n_days - i)
        sleep_hours = 5.0 + (i % 6) * 0.6  # varies 5.0 .. 8.0
        commits = int(sleep_hours)         # execution tracks sleep
        health_records.append(
            {
                "source_id": f"sleep-{day.isoformat()}",
                "type": "sleep_duration",
                "value": sleep_hours,
                "unit": "h",
                "start_time": _iso(day, 1),
                "end_time": _iso(day, 8),
            }
        )
        client.post(
            "/github",
            json={"day": day.isoformat(), "commits": commits, "coding_minutes": commits * 30},
        )
        client.post(
            "/mood",
            json={
                "day": day.isoformat(),
                "mood": min(5, max(1, commits - 2)),
                "energy": 3,
                "focus": min(5, max(1, commits - 2)),
                "stress": 2,
            },
        )

    res = client.post("/health/batch", json={"records": health_records})
    assert res.status_code == 200
    body = res.json()
    assert body["inserted"] == n_days
    return today, n_days


def test_root_and_health(client):
    assert client.get("/").json()["status"] == "ok"
    assert client.get("/healthz").json()["status"] == "healthy"


def test_health_ingest_is_idempotent(client):
    record = {
        "source_id": "abc-1",
        "type": "steps",
        "value": 1000,
        "unit": "count",
        "start_time": _iso(date.today()),
        "end_time": _iso(date.today(), 1),
    }
    first = client.post("/health/batch", json={"records": [record]}).json()
    second = client.post("/health/batch", json={"records": [record]}).json()
    assert first["inserted"] == 1
    assert second["inserted"] == 0
    assert second["skipped"] == 1


def test_mood_upsert(client):
    today = date.today().isoformat()
    client.post("/mood", json={"day": today, "mood": 3, "energy": 3, "focus": 3, "stress": 3})
    client.post("/mood", json={"day": today, "mood": 5, "energy": 4, "focus": 4, "stress": 2})
    got = client.get(f"/mood/{today}").json()
    assert got["mood"] == 5


def test_recompute_produces_scores_and_insights(client):
    _seed(client, n_days=20)
    res = client.post("/analytics/recompute?days=25").json()
    assert res["days_scored"] == 25
    assert res["insights_found"] >= 1

    scores = client.get("/analytics/scores?days=25").json()
    assert any(s["sleep_score"] is not None for s in scores)
    assert any(s["execution_score"] is not None for s in scores)

    insights = client.get("/analytics/insights").json()
    assert len(insights) >= 1
    # Sleep should relate to execution in the seeded data.
    drivers = {i["driver"] for i in insights}
    assert "sleep_score" in drivers


def test_state_endpoint(client):
    _seed(client, n_days=15)
    client.post("/analytics/recompute?days=20")
    states = client.get("/analytics/state?days=20").json()
    assert len(states) >= 1
    assert "state" in states[0]


def test_chat_grounded_fallback_when_openclaw_down(client):
    _seed(client, n_days=15)
    client.post("/analytics/recompute?days=20")
    res = client.post("/chat", json={"message": "Why am I unproductive?", "context_days": 20})
    assert res.status_code == 200
    body = res.json()
    assert body["grounded"] is True
    # Fallback path cites real averages.
    assert len(body["citations"]) >= 1


def test_chat_without_data_asks_for_more(client):
    res = client.post("/chat", json={"message": "What should I do today?"})
    body = res.json()
    assert "data" in body["answer"].lower()
    assert body["citations"] == []


def test_notifications_endpoint(client):
    _seed(client, n_days=15)
    client.post("/analytics/recompute?days=20")
    res = client.get("/notifications")
    assert res.status_code == 200
    assert isinstance(res.json(), list)
