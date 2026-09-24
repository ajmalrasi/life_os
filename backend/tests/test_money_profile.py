"""GET/PUT /money/profile (Stage 1 of mentor-insights)."""

from __future__ import annotations


def test_get_empty_returns_nulls(client):
    resp = client.get("/money/profile")
    assert resp.status_code == 200
    body = resp.json()
    assert body == {"monthly_income": None, "savings_goal_pct": None, "updated_at": None}


def test_put_then_get_round_trips(client):
    resp = client.put(
        "/money/profile",
        json={"monthly_income": "85000.00", "savings_goal_pct": 20},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["monthly_income"] == "85000.00"
    assert body["savings_goal_pct"] == 20
    assert body["updated_at"] is not None

    again = client.get("/money/profile").json()
    assert again["monthly_income"] == "85000.00"
    assert again["savings_goal_pct"] == 20


def test_put_upserts_singleton_row(client):
    client.put("/money/profile", json={"monthly_income": "50000.00", "savings_goal_pct": 10})
    resp = client.put("/money/profile", json={"monthly_income": "60000.00", "savings_goal_pct": 15})
    body = resp.json()
    assert body["monthly_income"] == "60000.00"
    assert body["savings_goal_pct"] == 15


def test_put_with_nulls_clears(client):
    client.put("/money/profile", json={"monthly_income": "50000.00", "savings_goal_pct": 10})
    resp = client.put("/money/profile", json={"monthly_income": None, "savings_goal_pct": None})
    assert resp.status_code == 200
    body = resp.json()
    assert body["monthly_income"] is None
    assert body["savings_goal_pct"] is None


def test_invalid_values_rejected(client):
    assert client.put("/money/profile", json={"monthly_income": "-5"}).status_code == 422
    assert client.put("/money/profile", json={"monthly_income": "0"}).status_code == 422
    assert client.put("/money/profile", json={"monthly_income": "20000000"}).status_code == 422
    assert client.put("/money/profile", json={"savings_goal_pct": -1}).status_code == 422
    assert client.put("/money/profile", json={"savings_goal_pct": 91}).status_code == 422
