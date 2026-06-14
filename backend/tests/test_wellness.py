"""Tests for the wellness score (Phase 1 #1)."""

from __future__ import annotations

from app.services.scoring import WellnessInputs, wellness_score


def test_wellness_none_when_no_pulse():
    assert wellness_score(WellnessInputs()) is None


def test_wellness_bounds():
    assert wellness_score(WellnessInputs(mood=5, energy=5, focus=5, stress=1)) == 100.0
    assert wellness_score(WellnessInputs(mood=1, energy=1, focus=1, stress=5)) == 0.0


def test_wellness_stress_is_inverted():
    calm = wellness_score(WellnessInputs(mood=4, energy=4, focus=4, stress=1))
    stressed = wellness_score(WellnessInputs(mood=4, energy=4, focus=4, stress=5))
    assert stressed < calm


def test_wellness_partial_data():
    # Only mood present → midpoint maps to 50.
    assert wellness_score(WellnessInputs(mood=3)) == 50.0


def test_wellness_clamps_out_of_range():
    assert wellness_score(WellnessInputs(mood=9)) == 100.0
    assert wellness_score(WellnessInputs(stress=0, mood=1, energy=1, focus=1)) == 0.0


def test_wellness_in_recompute_output(client):
    # Seed a mood check-in and recompute; the scores endpoint should expose wellness.
    from datetime import date

    today = date.today().isoformat()
    res = client.post(
        "/mood",
        json={"day": today, "mood": 4, "energy": 4, "focus": 3, "stress": 2},
    )
    assert res.status_code in (200, 201)

    client.post("/analytics/recompute?days=3")
    scores = client.get("/analytics/scores?days=3").json()
    todays = [s for s in scores if s["day"] == today]
    assert todays, "expected a score row for today"
    assert "wellness_score" in todays[0]
    assert todays[0]["wellness_score"] is not None
