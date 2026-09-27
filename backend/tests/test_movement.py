"""Places on the server: upload, payment matching, facts and the Ask tool."""

from __future__ import annotations

from datetime import date, datetime, timedelta
from decimal import Decimal
from zoneinfo import ZoneInfo

from sqlalchemy import select

from app.api.routes.statements import build_categorizer
from app.database import SessionLocal
from app.models.transaction import Transaction
from app.repositories.expense_repo import ExpenseRepository
from app.services.agent_tools import AgentTools
from app.services.expense_parsers.base import ParsedTxn
from app.services.movement import load_days, movement_evidence
from app.services.weekly_recap import compute_weekly_facts

IST = ZoneInfo("Asia/Kolkata")
TODAY = date(2026, 9, 24)  # Thursday; the recap week is Sep 17-23
DAY = date(2026, 9, 20)


def _at(d: date, hh: int, mm: int = 0) -> datetime:
    return datetime(d.year, d.month, d.day, hh, mm, tzinfo=IST)


def _stop(sid: str, d: date, start: tuple[int, int], end: tuple[int, int] | None, kind=None, name=None):
    return {
        "id": sid, "name": name or sid, "kind": kind, "latitude": 12.97161, "longitude": 77.59461,
        "arrived_at": _at(d, *start).isoformat(),
        "departed_at": _at(d, *end).isoformat() if end else None,
    }


def _day(d: date, stops: list[dict], **overrides) -> dict:
    return {
        "day": d.isoformat(), "distance_km": 42.5, "vehicle_km": 40.0, "away_minutes": 300,
        "late_away_minutes": 90, "farthest_km": 18.2, "stops": stops, **overrides,
    }


def _spend(amount: str, when: datetime | None, category: str, d: date = DAY, key: str | None = None):
    with SessionLocal() as db:
        ExpenseRepository(db).upsert(
            ParsedTxn(txn_date=d, amount=Decimal(amount), direction="debit", source="upi",
                      counterparty=f"PAYEE {key or amount}", txn_at=when),
            message_id=f"m-{key or amount}", dedup_key=f"k-{key or amount}",
        )
        db.commit()
        row = db.execute(select(Transaction).where(Transaction.dedup_key == f"k-{key or amount}")).scalar_one()
        row.spend_category_override = category
        db.commit()


def _upload(client, days):
    return client.put("/movement/days", json={"days": days})


def test_upload_is_idempotent_and_replaces_removed_stops(client):
    first = _upload(client, [_day(DAY, [_stop("a", DAY, (10, 0), (11, 0)), _stop("b", DAY, (12, 0), (13, 0))])])
    assert first.json() == {"days": 1, "stops": 2}
    again = _upload(client, [_day(DAY, [_stop("a", DAY, (10, 0), (11, 0))], distance_km=50.0)])
    assert again.status_code == 200
    body = client.get("/movement/days", params={"start": DAY.isoformat(), "end": DAY.isoformat()}).json()
    assert body["recorded_days"] == 1
    assert body["days"][0]["distance_km"] == 50.0
    assert [s["name"] for s in body["days"][0]["stops"]] == ["a"]


def test_payments_are_placed_at_stops_and_only_away_from_home_counts_as_out(client):
    _upload(client, [_day(DAY, [
        _stop("home", DAY, (0, 0), (18, 0), kind="home", name="Home"),
        _stop("cafe", DAY, (19, 0), (21, 0), name="Near Indiranagar"),
    ])])
    _spend("100", _at(DAY, 9), "groceries")           # at home
    _spend("450", _at(DAY, 21, 4), "food")            # just after leaving the cafe
    _spend("60", _at(DAY, 23), "food")                # nowhere recorded
    _spend("999", None, "shopping")                   # no time from the bank
    _spend("5000", _at(DAY, 20), "emi")               # EMI is not paid anywhere
    with SessionLocal() as db:
        [view] = load_days(db, DAY, DAY, build_categorizer(db), now=_at(DAY, 23, 59))
    placed = {p.txn.amount: (p.stop.name if p.stop else None) for p in view.payments}
    assert placed == {Decimal("100"): "Home", Decimal("450"): "Near Indiranagar", Decimal("60"): None, Decimal("999"): None}
    assert view.spent_while_out == Decimal("450")


def test_evidence_reports_the_week_late_outings_and_fuel_per_km(client):
    week = [date(2026, 9, 17) + timedelta(days=i) for i in range(7)]
    _upload(client, [_day(d, [_stop(f"s{d.day}", d, (21, 0), (23, 30), name="Highway dhaba")]) for d in week]
            + [_day(date(2026, 9, 12), [], late_away_minutes=0)])
    _spend("800", _at(DAY, 22), "food", key="dinner")
    _spend("1500", _at(date(2026, 9, 21), 21, 30), "fuel", d=date(2026, 9, 21), key="fuel")
    with SessionLocal() as db:
        facts = dict(movement_evidence(db, build_categorizer(db), TODAY))
    assert "7 day(s) recorded" in facts["places:week"]
    assert "297.5 km travelled" in facts["places:week"]
    assert "The week before: 1 day(s) recorded" in facts["places:week"]
    assert "Rs 2,300 spent while out" in facts["places:week"]
    late = [k for k in facts if k.startswith("places:late:")]
    assert len(late) == 3
    assert "spent Rs 1,500 while out (fuel Rs 1,500)" in facts["places:late:2026-09-21"]
    assert "320 km in a vehicle and Rs 1,500 on fuel (Rs 4.7 per vehicle km)" in facts["places:fuel"]


def test_trip_days_are_labelled_and_never_listed_as_late_outings(client):
    _upload(client, [_day(DAY, [], trip_title="Delhi")])
    with SessionLocal() as db:
        facts = dict(movement_evidence(db, build_categorizer(db), TODAY))
    assert "were on the trip 'Delhi'" in facts["places:week"]
    assert not [k for k in facts if k.startswith("places:late:")]


def test_nothing_recorded_means_no_facts(client):
    with SessionLocal() as db:
        assert movement_evidence(db, build_categorizer(db), TODAY) == []


def test_weekly_recap_includes_places(client):
    _upload(client, [_day(DAY, [])])
    with SessionLocal() as db:
        ids = [f.id for f in compute_weekly_facts(db, TODAY)]
    assert "places:week" in ids


def test_ask_tool_returns_days_with_stops_and_payments(client):
    _upload(client, [_day(DAY, [_stop("cafe", DAY, (19, 0), (21, 0), name="Cafe")])])
    _spend("450", _at(DAY, 20), "food")
    with SessionLocal() as db:
        result = AgentTools(db).run("get_movement", {"start_date": DAY.isoformat(), "end_date": DAY.isoformat()})
    day = result["days"][0]
    assert day["stops"][0]["name"] == "Cafe"
    assert day["stops"][0]["payments"][0]["amount"] == 450.0
    assert day["spent_while_out"] == 450.0
    assert result["source"] == "places"


def test_delete_removes_everything(client):
    _upload(client, [_day(DAY, [_stop("a", DAY, (10, 0), (11, 0))])])
    assert client.delete("/movement").status_code == 204
    body = client.get("/movement/days", params={"start": DAY.isoformat(), "end": DAY.isoformat()}).json()
    assert body["recorded_days"] == 0
