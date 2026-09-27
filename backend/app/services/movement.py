"""Places on the server: stored day summaries and stops, payments matched to
them, and the facts the model judges travel habits from.

The phone owns the recording (raw route stays there). Payments are matched
here with the phone's own stop rule (a payment within 5 minutes of a stay was
made there), so what Ask and Insights say agrees with the Places page. The
phone also matches payments made on the move from its raw route; the server
has only stops, so those count as not placed here.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from typing import Callable
from zoneinfo import ZoneInfo

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from app.models.movement import MovementDay, PlaceStop
from app.models.transaction import Transaction
from app.repositories.expense_repo import ExpenseRepository
from app.schemas.movement import MovementDayIn
from app.services.reconcile import EXCLUDED_FROM_SPEND

IST = ZoneInfo("Asia/Kolkata")
STOP_MARGIN = timedelta(minutes=5)
# Money's default spend view leaves EMI out; an EMI isn't paid anywhere.
_NOT_PLACE_SPEND = {"emi", "emi_fees"}

# How the model should read Places. Shared by every prompt that sees it.
MOVEMENT_JUDGEMENT_RULE = (
    "Judge travel and going out as a pattern over weeks, never one outing. Use the "
    "recorded kilometres, time out of home, hours out after 10 PM and money spent "
    "while out, and compare this week with last. Frequent late drives or outings that "
    "come with spending (food, fuel, travel) are worth naming plainly when they are "
    "rising or costly; a normal commute, errands, or one evening out are not. Days "
    "marked as a trip day were planned travel: never call them unnecessary. Days "
    "Places did not record are unknown, not zero."
)


def _aware(dt: datetime) -> datetime:
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def day_bounds(d: date) -> tuple[datetime, datetime]:
    start = datetime.combine(d, datetime.min.time(), tzinfo=IST)
    return start, start + timedelta(days=1)


def store_days(db: Session, days: list[MovementDayIn]) -> tuple[int, int]:
    """Upsert each day's summary and replace the stops that began that day."""
    stop_count = 0
    for payload in days:
        row = db.get(MovementDay, payload.day) or MovementDay(day=payload.day)
        row.distance_km = round(payload.distance_km, 2)
        row.vehicle_km = round(payload.vehicle_km, 2)
        row.away_minutes = payload.away_minutes
        row.late_away_minutes = payload.late_away_minutes
        row.farthest_km = None if payload.farthest_km is None else round(payload.farthest_km, 2)
        row.stop_count = len(payload.stops)
        row.trip_title = payload.trip_title
        db.merge(row)
        # Stops the user removed on the phone disappear here too.
        db.execute(delete(PlaceStop).where(PlaceStop.day == payload.day))
        for stop in payload.stops:
            db.merge(PlaceStop(
                id=stop.id,
                day=payload.day,
                name=stop.name,
                kind=stop.kind,
                latitude=round(stop.latitude, 3),
                longitude=round(stop.longitude, 3),
                arrived_at=stop.arrived_at,
                departed_at=stop.departed_at,
                trip_title=stop.trip_title,
            ))
            stop_count += 1
    db.commit()
    return len(days), stop_count


def delete_all(db: Session) -> None:
    db.execute(delete(PlaceStop))
    db.execute(delete(MovementDay))
    db.commit()


@dataclass
class PlacedPayment:
    txn: Transaction
    category: str
    stop: PlaceStop | None

    @property
    def while_out(self) -> bool:
        return self.stop is not None and self.stop.kind != "home"


@dataclass
class DayView:
    summary: MovementDay
    stops: list[PlaceStop]
    payments: list[PlacedPayment] = field(default_factory=list)

    @property
    def spent_while_out(self) -> Decimal:
        return sum((p.txn.amount for p in self.payments if p.while_out), Decimal(0))

    def spent_while_out_by_category(self) -> dict[str, Decimal]:
        totals: dict[str, Decimal] = {}
        for p in self.payments:
            if p.while_out:
                totals[p.category] = totals.get(p.category, Decimal(0)) + p.txn.amount
        return dict(sorted(totals.items(), key=lambda kv: kv[1], reverse=True))


def match_stop(paid_at: datetime | None, stops: list[PlaceStop], now: datetime) -> PlaceStop | None:
    """The stay (widened by 5 minutes) that contains the payment, nearest arrival first."""
    if paid_at is None:
        return None
    paid_at = _aware(paid_at)
    inside = [
        s for s in stops
        if _aware(s.arrived_at) - STOP_MARGIN <= paid_at <= _aware(s.departed_at or now) + STOP_MARGIN
    ]
    return min(inside, key=lambda s: abs((paid_at - _aware(s.arrived_at)).total_seconds()), default=None)


def load_days(
    db: Session, start: date, end: date, categorize: Callable[[str], str], now: datetime | None = None,
) -> list[DayView]:
    """Recorded days in [start, end], oldest first, with their stops and matched spend."""
    now = now or datetime.now(timezone.utc)
    summaries = list(db.execute(
        select(MovementDay).where(MovementDay.day >= start, MovementDay.day <= end).order_by(MovementDay.day)
    ).scalars())
    if not summaries:
        return []
    stops = list(db.execute(
        select(PlaceStop).where(PlaceStop.day >= start, PlaceStop.day <= end).order_by(PlaceStop.arrived_at)
    ).scalars())
    by_day = {s.day: DayView(s, []) for s in summaries}
    for stop in stops:
        if stop.day in by_day:
            by_day[stop.day].stops.append(stop)
    for txn in ExpenseRepository(db).range(start, end):
        if txn.direction != "debit" or txn.category in EXCLUDED_FROM_SPEND:
            continue
        category = txn.spend_category_override or categorize(txn.counterparty or "") or "uncategorized"
        if category in _NOT_PLACE_SPEND:
            continue
        view = by_day.get(txn.txn_date)
        if view is None:
            continue
        # A stay can start the evening before, so look at the neighbouring day's stops too.
        nearby = view.stops + (by_day[txn.txn_date - timedelta(days=1)].stops
                               if txn.txn_date - timedelta(days=1) in by_day else [])
        view.payments.append(PlacedPayment(txn, category, match_stop(txn.txn_at, nearby, now)))
    return [by_day[d] for d in sorted(by_day)]


def _money(amount: Decimal | float) -> str:
    return f"Rs {Decimal(amount):,.0f}"


def _hours(minutes: int) -> str:
    return f"{minutes / 60:.1f} h"


def _week_line(views: list[DayView]) -> str:
    km = sum(v.summary.distance_km for v in views)
    vehicle = sum(v.summary.vehicle_km for v in views)
    parts = [f"{len(views)} day(s) recorded", f"{km:.1f} km travelled ({vehicle:.1f} km in a vehicle)"]
    if any(v.summary.away_minutes is not None for v in views):
        parts.append(f"{_hours(sum(v.summary.away_minutes or 0 for v in views))} out of home")
        parts.append(f"{_hours(sum(v.summary.late_away_minutes or 0 for v in views))} of it after 10 PM")
        parts.append(f"{_money(sum((v.spent_while_out for v in views), Decimal(0)))} spent while out")
    return ", ".join(parts)


def movement_week_facts(
    db: Session, categorize: Callable[[str], str], start: date, end: date, prev_start: date, prev_end: date,
) -> list[tuple[str, str]]:
    """(id, statement) for this week vs last. Empty when Places has recorded nothing."""
    this_week = load_days(db, start, end, categorize)
    if not this_week:
        return []
    last_week = load_days(db, prev_start, prev_end, categorize)
    statement = f"Places {start:%b} {start.day} to {end:%b} {end.day}: {_week_line(this_week)}"
    if last_week:
        statement += f". The week before: {_week_line(last_week)}"
    trip_days = [v for v in this_week if v.summary.trip_title]
    if trip_days:
        statement += f". {len(trip_days)} of these day(s) were on the trip '{trip_days[0].summary.trip_title}'"
    return [("places:week", statement + ".")]


def movement_evidence(db: Session, categorize: Callable[[str], str], today: date) -> list[tuple[str, str]]:
    """Facts for Insights: the week, the latest late outings, and fuel against distance."""
    from app.services.weekly_recap import week_bounds

    prev_start, prev_end, start, end = week_bounds(today)
    facts = movement_week_facts(db, categorize, start, end, prev_start, prev_end)
    if not facts:
        return []
    recent = load_days(db, today - timedelta(days=13), today, categorize)
    late = [v for v in recent if (v.summary.late_away_minutes or 0) > 0 and not v.summary.trip_title]
    for view in sorted(late, key=lambda v: v.summary.day, reverse=True)[:3]:
        s = view.summary
        text = (
            f"{s.day:%a} {s.day:%b} {s.day.day}: out {_hours(s.late_away_minutes or 0)} after 10 PM, "
            f"{s.distance_km:.1f} km travelled"
        )
        if s.farthest_km is not None:
            text += f", farthest {s.farthest_km:.1f} km from home"
        spent = view.spent_while_out
        if spent:
            split = ", ".join(f"{c} {_money(a)}" for c, a in list(view.spent_while_out_by_category().items())[:3])
            text += f"; spent {_money(spent)} while out ({split})"
        facts.append((f"places:late:{s.day.isoformat()}", text + "."))
    month = load_days(db, today - timedelta(days=29), today, categorize)
    vehicle_km = sum(v.summary.vehicle_km for v in month)
    fuel = sum((p.txn.amount for v in month for p in v.payments if p.category == "fuel"), Decimal(0))
    if vehicle_km >= 1 and fuel > 0:
        facts.append((
            "places:fuel",
            f"Last 30 days on recorded days: {vehicle_km:.0f} km in a vehicle and {_money(fuel)} on fuel "
            f"(Rs {fuel / Decimal(str(vehicle_km)):.1f} per vehicle km).",
        ))
    return facts


def movement_window(db: Session, start: date, end: date, categorize: Callable[[str], str]) -> dict:
    """What Ask sees: each recorded day with its stops and the payments made at them."""
    days = []
    for view in load_days(db, start, end, categorize):
        s = view.summary
        stops = []
        for stop in view.stops:
            paid = [p for p in view.payments if p.stop is not None and p.stop.id == stop.id]
            stops.append({
                "name": stop.name,
                "kind": stop.kind,
                "arrived": _aware(stop.arrived_at).astimezone(IST).strftime("%H:%M"),
                "left": _aware(stop.departed_at).astimezone(IST).strftime("%H:%M") if stop.departed_at else None,
                "payments": [
                    {"amount": float(p.txn.amount), "payee": p.txn.counterparty, "category": p.category}
                    for p in paid
                ],
            })
        unplaced = [p for p in view.payments if p.stop is None]
        days.append({
            "day": s.day.isoformat(),
            "trip": s.trip_title,
            "distance_km": s.distance_km,
            "vehicle_km": s.vehicle_km,
            "away_hours": None if s.away_minutes is None else round(s.away_minutes / 60, 1),
            "late_away_hours": None if s.late_away_minutes is None else round(s.late_away_minutes / 60, 1),
            "farthest_km": s.farthest_km,
            "spent_while_out": float(view.spent_while_out),
            "stops": stops,
            "payments_without_a_place": [
                {"amount": float(p.txn.amount), "payee": p.txn.counterparty, "category": p.category}
                for p in unplaced
            ],
        })
    return {
        "start_date": start.isoformat(),
        "end_date": end.isoformat(),
        "recorded_days": len(days),
        "days": days,
        "note": "Only days Places recorded are listed; other days are unknown. Money in INR.",
    }
