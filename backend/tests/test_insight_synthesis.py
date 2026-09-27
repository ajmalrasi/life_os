"""The mentor-style LLM-synthesized Insights page.

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

    def chat_json(self, messages, schema, schema_name, max_tokens=512, temperature=0.0, timeout=None) -> dict:
        # The response is written as the items of the "insights" array.
        import json

        self.calls += 1
        self.schema = schema
        if self.fail:
            raise OpenClawUnavailable("down")
        try:
            return {"insights": json.loads(self.response)}
        except ValueError:
            return {"insights": []}


def _evidence() -> list[EvidenceFact]:
    return [
        EvidenceFact("cat:shopping", "Shopping: ₹25,000 this month (30% of this month's spend), usual month ₹8,000."),
        EvidenceFact("cat:vehicle", "Vehicle maintenance: ₹6,000 this month, usual month ₹1,500."),
        EvidenceFact("money:income", "Monthly take-home income (entered by the user): ₹80,000."),
    ]


def _item(**overrides) -> str:
    import json

    base = {
        "title": "Shopping is too high",
        "verdict": "You spent ₹25,000 on shopping this month against a usual ₹8,000.",
        "action": "Cap shopping at ₹10,000 for the rest of the month.",
        "domain": "money",
        "severity": "high",
        "cites": ["cat:shopping"],
    }
    base.update(overrides)
    return json.dumps(base)


def test_synthesize_keeps_grounded_insights():
    client = _FakeClient(f"[{_item()}]")
    out = synthesize(_evidence(), client=client)
    assert len(out) == 1
    assert out[0]["title"] == "Shopping is too high"
    assert out[0]["cites"] == ["cat:shopping"]
    assert out[0]["theme"] == "money"
    assert out[0]["severity"] == "high"
    assert out[0]["action"] == "Cap shopping at ₹10,000 for the rest of the month."


def test_synthesize_drops_insight_citing_only_unknown_ids():
    """An insight grounded in nothing we handed the model is an invented
    judgement — it must be dropped, not stored."""
    client = _FakeClient(f"[{_item(cites=['cat:made_up'])}]")
    assert synthesize(_evidence(), client=client) == []


def test_synthesize_strips_unknown_cites_but_keeps_valid_one():
    client = _FakeClient(f"[{_item(cites=['cat:shopping', 'cat:made_up'])}]")
    out = synthesize(_evidence(), client=client)
    assert out and out[0]["cites"] == ["cat:shopping"]


def test_synthesize_requires_cites():
    client = _FakeClient(f"[{_item(cites=[])}]")
    assert synthesize(_evidence(), client=client) == []


def test_missing_action_stored_as_none_not_dropped():
    import json

    item = json.loads(_item())
    del item["action"]
    client = _FakeClient(f"[{json.dumps(item)}]")
    out = synthesize(_evidence(), client=client)
    assert len(out) == 1
    assert out[0]["action"] is None


def test_unknown_domain_and_severity_clamp_to_defaults():
    client = _FakeClient(f"[{_item(domain='crypto', severity='urgent')}]")
    out = synthesize(_evidence(), client=client)
    assert out[0]["theme"] == "life"
    assert out[0]["severity"] == "medium"


def test_synthesize_caps_to_five_and_returned_count_can_be_seven():
    items = ",".join(_item(cites=["cat:shopping"], title=f"t{chr(97 + i)}") for i in range(7))
    client = _FakeClient(f"[{items}]")
    assert len(synthesize(_evidence(), client=client)) == 5


def test_synthesize_sorts_by_severity_high_to_low():
    items = ",".join(
        [
            _item(title="low one", severity="low", cites=["cat:shopping"]),
            _item(title="high one", severity="high", cites=["cat:shopping"]),
            _item(title="medium one", severity="medium", cites=["cat:shopping"]),
        ]
    )
    client = _FakeClient(f"[{items}]")
    out = synthesize(_evidence(), client=client)
    assert [d["severity"] for d in out] == ["high", "medium", "low"]


def test_synthesize_empty_when_unavailable():
    assert synthesize(_evidence(), client=_FakeClient(fail=True)) == []


def test_synthesize_empty_on_junk():
    assert synthesize(_evidence(), client=_FakeClient("not json")) == []


def test_synthesize_no_evidence_short_circuits():
    client = _FakeClient(f"[{_item()}]")
    assert synthesize([], client=client) == []
    assert client.calls == 0


def test_synthesize_skips_when_disabled(monkeypatch):
    monkeypatch.setattr(insight_synthesizer.settings, "insight_synthesis_enabled", False)
    client = _FakeClient(f"[{_item()}]")
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
            {
                "title": "Stress spend",
                "body": "You spend more after stress.",
                "action": "Take a walk instead of ordering in.",
                "theme": "money",
                "severity": "medium",
                "cites": [evidence[0].id],
            }
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
    assert body[0]["action"] == "Take a walk instead of ordering in."
    assert body[0]["cites"]  # grounded citation round-tripped


def test_refresh_endpoint_returns_new_rows(client, monkeypatch):
    import app.api.routes.analytics as analytics_routes

    monkeypatch.setattr(
        analytics_routes,
        "build_life_evidence",
        lambda db: [EvidenceFact("money:income", "Monthly income: ₹50,000.")],
    )
    monkeypatch.setattr(
        analytics_routes.insight_synthesizer,
        "synthesize",
        lambda evidence, today_label=None: [
            {
                "title": "Fresh insight",
                "body": "Body.",
                "action": None,
                "theme": "money",
                "severity": "low",
                "cites": ["money:income"],
            }
        ],
    )
    resp = client.post("/analytics/synthesis/refresh")
    assert resp.status_code == 200
    body = resp.json()
    assert body and body[0]["title"] == "Fresh insight"
    assert "X-Insights-Stale" not in resp.headers


def test_refresh_endpoint_keeps_old_rows_and_flags_stale_when_synthesis_empty(client, monkeypatch):
    import app.api.routes.analytics as analytics_routes
    from app.database import SessionLocal
    from app.repositories.analytics_repo import SynthesizedInsightRepository

    with SessionLocal() as db:
        SynthesizedInsightRepository(db).replace(
            [{"title": "Old insight", "body": "Old body.", "theme": "money", "cites": ["x"]}]
        )

    monkeypatch.setattr(analytics_routes, "build_life_evidence", lambda db: [])
    monkeypatch.setattr(
        analytics_routes.insight_synthesizer, "synthesize", lambda evidence, today_label=None: []
    )
    resp = client.post("/analytics/synthesis/refresh")
    assert resp.status_code == 200
    body = resp.json()
    assert body and body[0]["title"] == "Old insight"
    assert resp.headers.get("X-Insights-Stale") == "1"


def test_synthesize_drops_an_insight_with_a_number_not_in_the_evidence():
    evidence = _evidence() + [EvidenceFact(
        "health:workouts",
        "Right now: the most recent workout was 58 days ago, with 0 sessions in the past 7 days. "
        "Current gap, still open: Jul 29 to today (58 days so far).",
    )]
    wrong = _item(title="Workouts have lapsed", verdict="No substantial workout for 45 days.",
                  domain="health", cites=["health:workouts"])
    right = _item(title="Workouts have lapsed", verdict="No workout for 58 days since Jul 29.",
                  domain="health", cites=["health:workouts"])
    assert synthesize(evidence, client=_FakeClient(f"[{wrong}]")) == []
    kept = synthesize(evidence, client=_FakeClient(f"[{right}]"))
    assert [i["body"] for i in kept] == ["No workout for 58 days since Jul 29."]


def test_schema_bounds_lengths_and_limits_cites_to_given_facts():
    client = _FakeClient(f"[{_item()}]")
    synthesize(_evidence(), client=client)
    item = client.schema["properties"]["insights"]["items"]["properties"]
    assert item["verdict"]["maxLength"] <= 400
    assert item["cites"]["items"]["enum"] == ["cat:shopping", "cat:vehicle", "money:income"]


def test_lapse_claim_dropped_when_no_gap_is_open():
    """It called workouts 'lapsed for 37 days' the day after a workout."""
    active = _evidence() + [EvidenceFact(
        "health:workouts",
        "Right now: the most recent workout was 1 day ago, with 6 sessions in the past 7 days. "
        "Earlier gaps of a week or more, all ended since: Jul 29 to Sep 4 (37 days).",
    )]
    lapsed = _item(title="Workouts lapsed for 37 days", verdict="Your routine broke for 37 days.",
                   domain="health", cites=["health:workouts"])
    restart = _item(title="Workouts are restarting", verdict="6 sessions in the past 7 days after a 37 days gap.",
                    domain="health", cites=["health:workouts"])
    out = synthesize(active, client=_FakeClient(f"[{lapsed},{restart}]"))
    assert [i["title"] for i in out] == ["Workouts are restarting"]

    idle = _evidence() + [EvidenceFact(
        "health:workouts",
        "Right now: the most recent workout was 21 days ago. Current gap, still open: Sep 4 to today (21 days so far).",
    )]
    still = _item(title="Workouts lapsed", verdict="No workout for 21 days.", domain="health", cites=["health:workouts"])
    assert len(synthesize(idle, client=_FakeClient(f"[{still}]"))) == 1
