"""Cross-domain evidence bundle for the mentor-style Insights synthesizer.

Pure computation, no LLM calls here — every number the mentor prompt can cite
is computed in this module first. Money uses the same effective-category logic
and spend exclusions as the Money page (`EXCLUDED_FROM_SPEND` +
`build_categorizer` + `spend_category_override`) so a fact here never
disagrees with what the user sees on their phone.

Each builder is wrapped so one broken domain (a missing table, an empty
window, a bad row) never takes the others down with it — `build_life_evidence`
always returns whatever could be computed.
"""

from __future__ import annotations

import calendar
import logging
import statistics
from datetime import date, timedelta
from decimal import Decimal
from typing import Callable

from sqlalchemy.orm import Session

from app.services.training import workout_statement, workout_summary
from app.api.routes.statements import build_categorizer
from app.models.card_statement import CardStatement
from app.models.money_profile import MoneyProfile
from app.models.trip import TripBackup
from app.repositories.analytics_repo import InsightRepository, ScoreRepository
from app.repositories.expense_repo import ExpenseRepository
from app.repositories.github_repo import GithubRepository
from app.repositories.health_repo import HealthRepository
from app.repositories.mood_repo import MoodRepository
from app.models.health import HealthRecordType
from app.models.transaction import Transaction
from app.services.budgets import budget_pace_summary
from app.services.card_cycles import current_card_cycles
from app.services.insight_synthesizer import EvidenceFact
from app.services.recurring import detect_recurring_charges
from app.services.reconcile import EXCLUDED_FROM_SPEND
from app.services.weekly_recap import week_bounds
from sqlalchemy import select

logger = logging.getLogger("lifeos.life_evidence")

_MAX_FACTS = 45

_CATEGORY_LABELS = {
    "emi": "EMI",
    "emi_fees": "EMI fees",
    "vehicle": "Vehicle maintenance",
    "insurance": "Insurance",
    "taxes": "Taxes & government fees",
    "legal_fees": "Professional services",
}

_ESSENTIAL_CATEGORIES = {"food", "groceries", "bills", "utilities", "health"}
_DISCRETIONARY_CATEGORIES = {
    "shopping",
    "electronics",
    "entertainment",
    "travel",
    "vehicle",
    "fuel",
    "subscriptions",
}


def _label(category: str) -> str:
    return _CATEGORY_LABELS.get(category, category.replace("_", " ").title())


def _money(amount: Decimal | float) -> str:
    return f"₹{round(float(amount)):,}"


def _add_months(d: date, months: int) -> date:
    m = d.month - 1 + months
    y = d.year + m // 12
    m = m % 12 + 1
    day = min(d.day, calendar.monthrange(y, m)[1])
    return date(y, m, day)


def _month_bounds(today: date) -> dict:
    """MTD/prev-MTD/baseline windows shared by every money builder below."""
    current_month_start = today.replace(day=1)
    days_in_month = calendar.monthrange(today.year, today.month)[1]
    prev_month_start = _add_months(current_month_start, -1)
    days_in_prev_month = calendar.monthrange(prev_month_start.year, prev_month_start.month)[1]
    prev_mtd_end = prev_month_start.replace(day=min(today.day, days_in_prev_month))
    baseline_end = current_month_start - timedelta(days=1)
    baseline_start = _add_months(current_month_start, -3)
    last_month_end = baseline_end
    return {
        "mtd_start": current_month_start,
        "mtd_end": today,
        "days_elapsed": today.day,
        "days_in_month": days_in_month,
        "prev_mtd_start": prev_month_start,
        "prev_mtd_end": prev_mtd_end,
        "baseline_start": baseline_start,
        "baseline_end": baseline_end,
        "last_month_start": prev_month_start,
        "last_month_end": last_month_end,
    }


def _spend_rows(
    db: Session, categorize: Callable[[str], str], start: date, end: date
) -> list[tuple[Transaction, str]]:
    """(txn, effective_category) for real spend in [start, end], MTD-page semantics."""
    txns = ExpenseRepository(db).range(start, end)
    out: list[tuple[Transaction, str]] = []
    for t in txns:
        if t.direction != "debit" or t.category in EXCLUDED_FROM_SPEND:
            continue
        effective = t.spend_category_override or categorize(t.counterparty or "")
        out.append((t, effective or "uncategorized"))
    return out


def _totals_by_category(rows: list[tuple[Transaction, str]]) -> dict[str, Decimal]:
    totals: dict[str, Decimal] = {}
    for txn, category in rows:
        totals[category] = totals.get(category, Decimal(0)) + txn.amount
    return totals


# -- 2a: income & savings -----------------------------------------------------------


def _income_facts(
    db: Session, categorize: Callable[[str], str], today: date, bounds: dict
) -> list[EvidenceFact]:
    profile = db.get(MoneyProfile, 1)
    if profile is None or profile.monthly_income is None:
        return [
            EvidenceFact(
                id="money:income_missing",
                statement="Monthly income is not set, so spending cannot be judged against income.",
            )
        ]

    income = profile.monthly_income
    facts = [EvidenceFact(id="money:income", statement=f"Monthly take-home income (entered by the user): {_money(income)}.")]

    mtd_rows = _spend_rows(db, categorize, bounds["mtd_start"], bounds["mtd_end"])
    mtd_total = sum((t.amount for t, _ in mtd_rows), Decimal(0))
    d = bounds["days_elapsed"]
    n = bounds["days_in_month"]
    pace_total = (mtd_total / d * n) if d else Decimal(0)
    p = round(float(mtd_total) / float(income) * 100) if income else 0
    q = round(float(pace_total) / float(income) * 100) if income else 0
    facts.append(
        EvidenceFact(
            id="money:spend_vs_income",
            statement=(
                f"Spent {_money(mtd_total)} so far this month (day {d} of {n}), which is {p}% of "
                f"monthly income. At this pace the month ends near {_money(pace_total)} ({q}% of income)."
            ),
        )
    )

    last_rows = _spend_rows(db, categorize, bounds["last_month_start"], bounds["last_month_end"])
    last_total = sum((t.amount for t, _ in last_rows), Decimal(0))
    saved = income - last_total
    rate = round(float(saved) / float(income) * 100) if income else 0
    statement = f"Last full month: income {_money(income)} minus spend {_money(last_total)} = saved {_money(saved)} ({rate}% savings rate)."
    if profile.savings_goal_pct is not None:
        statement += f" Savings goal is {profile.savings_goal_pct}%."
    facts.append(EvidenceFact(id="money:savings", statement=statement))
    return facts


# -- 2b: month totals ----------------------------------------------------------------


def _month_total_facts(
    db: Session, categorize: Callable[[str], str], bounds: dict
) -> list[EvidenceFact]:
    mtd_rows = _spend_rows(db, categorize, bounds["mtd_start"], bounds["mtd_end"])
    a = sum((t.amount for t, _ in mtd_rows), Decimal(0))
    n_txn = len(mtd_rows)

    prev_rows = _spend_rows(db, categorize, bounds["prev_mtd_start"], bounds["prev_mtd_end"])
    b = sum((t.amount for t, _ in prev_rows), Decimal(0))

    baseline_rows = _spend_rows(db, categorize, bounds["baseline_start"], bounds["baseline_end"])
    baseline_total = sum((t.amount for t, _ in baseline_rows), Decimal(0))
    c = baseline_total / 3 if baseline_total else Decimal(0)

    statement = f"This month so far: {_money(a)} across {n_txn} transaction(s)"
    if b:
        pct = round((float(a) - float(b)) / float(b) * 100)
        sign = "+" if pct >= 0 else ""
        statement += f", vs {_money(b)} by the same day last month ({sign}{pct}%)"
    statement += f". 3-month average full month: {_money(c)}."
    return [EvidenceFact(id="money:mtd", statement=statement)]


# -- 2c/2d: categories & comparisons --------------------------------------------------


def _category_facts(
    db: Session, categorize: Callable[[str], str], bounds: dict
) -> tuple[list[EvidenceFact], dict[str, Decimal]]:
    mtd_rows = _spend_rows(db, categorize, bounds["mtd_start"], bounds["mtd_end"])
    mtd_by_cat = _totals_by_category(mtd_rows)
    mtd_total = sum(mtd_by_cat.values(), Decimal(0))

    baseline_rows = _spend_rows(db, categorize, bounds["baseline_start"], bounds["baseline_end"])
    baseline_by_cat = _totals_by_category(baseline_rows)
    baseline_avg_by_cat = {k: v / 3 for k, v in baseline_by_cat.items()}

    candidates = {
        cat
        for cat in set(mtd_by_cat) | set(baseline_avg_by_cat)
        if mtd_by_cat.get(cat, Decimal(0)) >= 1000 or baseline_avg_by_cat.get(cat, Decimal(0)) >= 1000
    }
    top = sorted(candidates, key=lambda c: mtd_by_cat.get(c, Decimal(0)), reverse=True)[:8]

    facts = []
    for cat in top:
        mtd_amt = mtd_by_cat.get(cat, Decimal(0))
        baseline_amt = baseline_avg_by_cat.get(cat, Decimal(0))
        pct_of_month = round(float(mtd_amt) / float(mtd_total) * 100) if mtd_total else 0
        statement = f"{_label(cat)}: {_money(mtd_amt)} this month ({pct_of_month}% of this month's spend), usual month {_money(baseline_amt)} (3-month avg)."
        if baseline_amt > 0:
            multiple = float(mtd_amt) / float(baseline_amt)
            statement += f" That is {multiple:.1f}x the usual."
        else:
            statement += " No spend in this category in the prior 3 months."
        if cat in ("emi", "emi_fees"):
            statement += " (fixed EMI commitment, not discretionary)"
        facts.append(EvidenceFact(id=f"cat:{cat}", statement=statement))

    return facts, mtd_by_cat


# "Other" and EMI make meaningless comparisons ("Travel is 17x Other").
_NOT_COMPARABLE = {"other", "emi", "emi_fees"}


def _comparison_facts(mtd_by_cat: dict[str, Decimal]) -> list[EvidenceFact]:
    comparable = {c: a for c, a in mtd_by_cat.items() if c not in _NOT_COMPARABLE}
    top6 = sorted(comparable.items(), key=lambda kv: kv[1], reverse=True)[:6]
    pairs: list[tuple[str, Decimal, str, Decimal, bool]] = []
    for i, (cat_a, amt_a) in enumerate(top6):
        for cat_b, amt_b in top6[i + 1 :]:
            bigger, big_amt, smaller, small_amt = (
                (cat_a, amt_a, cat_b, amt_b) if amt_a >= amt_b else (cat_b, amt_b, cat_a, amt_a)
            )
            if small_amt <= 0 or big_amt < small_amt * Decimal("1.5"):
                continue
            preferred = (bigger in _DISCRETIONARY_CATEGORIES and smaller in _ESSENTIAL_CATEGORIES) or (
                smaller in _DISCRETIONARY_CATEGORIES and bigger in _ESSENTIAL_CATEGORIES
            )
            pairs.append((bigger, big_amt, smaller, small_amt, preferred))

    pairs.sort(key=lambda p: (not p[4], -(float(p[1]) / float(p[3]))))
    facts = []
    used_bigger: set[str] = set()
    for bigger, big_amt, smaller, small_amt, _ in pairs:
        # One comparison per larger category, so a single outlier (e.g. a
        # trip month's travel) cannot fill every slot.
        if bigger in used_bigger or len(facts) >= 3:
            continue
        used_bigger.add(bigger)
        multiple = float(big_amt) / float(small_amt)
        facts.append(
            EvidenceFact(
                id=f"cmp:{bigger}_vs_{smaller}",
                statement=f"{_label(bigger)} ({_money(big_amt)}) is {multiple:.1f}x {_label(smaller)} ({_money(small_amt)}) this month.",
            )
        )
    return facts


# -- 2e: big one-off purchases --------------------------------------------------------


def _big_purchase_facts(
    db: Session, categorize: Callable[[str], str], today: date, bounds: dict
) -> list[EvidenceFact]:
    recent_rows = _spend_rows(db, categorize, today - timedelta(days=30), today)
    baseline_rows = _spend_rows(db, categorize, bounds["baseline_start"], bounds["baseline_end"])
    baseline_amounts = sorted(float(t.amount) for t, _ in baseline_rows)
    median = statistics.median(baseline_amounts) if baseline_amounts else 0.0
    threshold = max(5000.0, median * 3)

    big = sorted(
        [(t, cat) for t, cat in recent_rows if float(t.amount) >= threshold],
        key=lambda tc: tc[0].amount,
        reverse=True,
    )[:3]
    return [
        EvidenceFact(
            id=f"big:{t.id}",
            statement=f"{_money(t.amount)} to {t.counterparty or f'an unnamed payee ({t.account or t.source})'} on {t.txn_date.isoformat()} ({_label(cat)}).",
        )
        for t, cat in big
    ]


# -- 2f: budgets -----------------------------------------------------------------------


def _budget_facts(db: Session, categorize: Callable[[str], str], today: date) -> list[EvidenceFact]:
    facts = []
    for pace in budget_pace_summary(db, categorize, today=today):
        statement = (
            f"Budget for {_label(pace.category)}: {_money(pace.monthly_limit)}/month. "
            f"Spent {_money(pace.spent_so_far)} ({round(float(pace.spent_so_far) / float(pace.monthly_limit) * 100) if pace.monthly_limit else 0}%) "
            f"by day {pace.days_elapsed}; on pace for {_money(pace.projected_month_total)}"
        )
        if pace.spent_so_far > pace.monthly_limit:
            statement += f", already over the limit by {_money(pace.spent_so_far - pace.monthly_limit)}"
        elif pace.on_pace_to_exceed and pace.projected_exceed_day:
            statement += f", crosses the limit around day {pace.projected_exceed_day}"
        statement += "."
        facts.append(EvidenceFact(id=f"budget:{pace.category}", statement=statement))
    return facts


# -- 2g: fixed commitments -------------------------------------------------------------


def _fixed_commitment_facts(
    db: Session, categorize: Callable[[str], str], today: date, bounds: dict
) -> list[EvidenceFact]:
    facts: list[EvidenceFact] = []
    last_rows = _spend_rows(db, categorize, bounds["last_month_start"], bounds["last_month_end"])
    last_by_cat = _totals_by_category(last_rows)
    emi_total = last_by_cat.get("emi", Decimal(0)) + last_by_cat.get("emi_fees", Decimal(0))

    recurring = detect_recurring_charges(db, today=today)
    recurring_total = Decimal(0)
    for charge in recurring:
        monthly_equivalent = charge.median_amount * Decimal("4.3") if charge.cadence == "weekly" else charge.median_amount
        recurring_total += monthly_equivalent

    total = emi_total + recurring_total
    profile = db.get(MoneyProfile, 1)
    statement = f"Fixed monthly commitments: EMIs {_money(emi_total)} + recurring {_money(recurring_total)} = {_money(total)}"
    if profile is not None and profile.monthly_income:
        pct = round(float(total) / float(profile.monthly_income) * 100)
        statement += f", which is {pct}% of income"
    statement += "."
    facts.append(EvidenceFact(id="money:fixed", statement=statement))

    for i, charge in enumerate(recurring):
        if charge.price_change_pct is not None and charge.price_change_pct >= 5:
            facts.append(
                EvidenceFact(
                    id=f"recurring:price_up:{i}",
                    statement=f"{charge.counterparty} went up {charge.price_change_pct:.0f}% ({_money(charge.median_amount)} -> {_money(charge.last_amount)}).",
                )
            )
        if 0 <= (charge.next_expected_date - today).days <= 7:
            facts.append(
                EvidenceFact(
                    id=f"recurring:due_soon:{i}",
                    statement=f"{charge.counterparty} ({charge.cadence}, usually {_money(charge.median_amount)}) is expected around {charge.next_expected_date.isoformat()}.",
                )
            )
    return facts


# -- 2h: card bills ----------------------------------------------------------------------


def _card_facts(db: Session, today: date) -> list[EvidenceFact]:
    facts: list[EvidenceFact] = []
    upcoming = list(
        db.execute(
            select(CardStatement).where(
                CardStatement.paid_at.is_(None),
                CardStatement.due_date.is_not(None),
                CardStatement.due_date >= today,
                CardStatement.due_date <= today + timedelta(days=10),
            )
        ).scalars()
    )
    if upcoming:
        total = sum((s.total_due or Decimal(0) for s in upcoming), Decimal(0))
        items = "; ".join(
            f"{s.bank} ····{s.card_last4 or '?'} {_money(s.total_due or Decimal(0))} on {s.due_date.isoformat()}"
            for s in upcoming
        )
        facts.append(
            EvidenceFact(
                id="cards:due",
                statement=f"Card bills due in the next 10 days: {items}; total {_money(total)}.",
            )
        )

    cycles = current_card_cycles(db, as_of=today)
    if cycles.current_spend_total:
        facts.append(
            EvidenceFact(
                id="cards:cycle",
                statement=f"Spend already on open credit-card cycles (not yet billed): {_money(cycles.current_spend_total)}.",
            )
        )
    return facts


# -- 2i: trips ------------------------------------------------------------------------


def _trip_facts(db: Session, categorize: Callable[[str], str], today: date, bounds: dict) -> list[EvidenceFact]:
    facts: list[EvidenceFact] = []
    backups = list(
        db.execute(select(TripBackup).where(TripBackup.deleted_at.is_(None))).scalars()
    )
    considered: list[tuple[TripBackup, dict, str]] = []
    for backup in backups:
        try:
            payload = backup.payload or {}
            trip = payload.get("trip") or {}
            start_str, end_str = trip.get("start_date"), trip.get("end_date")
            if not start_str or not end_str:
                continue
            start_d = date.fromisoformat(start_str[:10])
            end_d = date.fromisoformat(end_str[:10])
            if start_d <= today <= end_d:
                status = "active"
            elif start_d > today and (start_d - today).days <= 45:
                status = f"starts in {(start_d - today).days} day(s)"
            elif end_d < today and (today - end_d).days <= 30:
                status = f"ended {(today - end_d).days} day(s) ago"
            else:
                continue
            considered.append((backup, payload, status))
        except Exception:
            logger.debug("skipping unparseable trip backup", exc_info=True)
    considered.sort(key=lambda item: item[0].updated_at, reverse=True)

    for backup, payload, status in considered[:2]:
        trip = payload.get("trip") or {}
        expenses = payload.get("expenses") or []
        currency = trip.get("currency", "INR")
        totals: dict[str, float] = {}
        total = 0.0
        for exp in expenses:
            try:
                if exp.get("currency", currency) != currency:
                    continue
                amt = float(exp.get("amount") or 0)
                total += amt
                cat = exp.get("category") or "other"
                totals[cat] = totals.get(cat, 0.0) + amt
            except Exception:
                continue
        top_cats = sorted(totals.items(), key=lambda kv: kv[1], reverse=True)[:2]
        top_str = ", ".join(f"{c} {currency} {round(v):,}" for c, v in top_cats) or "none"

        activities = payload.get("activities") or []
        done = sum(1 for a in activities if a.get("status") == "done")
        statement = (
            f"Trip '{trip.get('title', 'Untitled')}' {trip.get('start_date')} to {trip.get('end_date')} "
            f"({status}). Trip expenses logged: {currency} {round(total):,} (top: {top_str}). "
            f"Activities done: {done}/{len(activities)}."
        )
        facts.append(EvidenceFact(id=f"trip:{backup.id}", statement=statement))

    travel_rows = _spend_rows(db, categorize, today - timedelta(days=60), today)
    travel_spend = sum((t.amount for t, cat in travel_rows if cat == "travel"), Decimal(0))
    if travel_spend:
        baseline_rows = _spend_rows(db, categorize, bounds["baseline_start"], bounds["baseline_end"])
        baseline_travel = sum((t.amount for t, cat in baseline_rows if cat == "travel"), Decimal(0)) / 3
        threshold = baseline_travel * 2
        if threshold > 0 and travel_spend >= threshold:
            facts.append(
                EvidenceFact(
                    id="money:travel_vs_usual",
                    statement=f"Travel spend in the last 60 days is {_money(travel_spend)}, vs a usual monthly average of {_money(baseline_travel)}.",
                )
            )
    return facts


# -- 2j: life signals -------------------------------------------------------------------


def _life_signal_facts(db: Session, today: date) -> list[EvidenceFact]:
    facts: list[EvidenceFact] = []
    prev_start, prev_end, start, end = week_bounds(today)

    scores = ScoreRepository(db)
    this_week_scores = scores.range(start, end)
    prev_week_scores = scores.range(prev_start, prev_end)

    def _avg(values: list[float | None]) -> float | None:
        vals = [v for v in values if v is not None]
        return sum(vals) / len(vals) if vals else None

    days_with_sleep = sum(1 for s in this_week_scores if s.sleep_score is not None)
    if days_with_sleep >= 3:
        this_sleep = _avg([s.sleep_score for s in this_week_scores])
        prev_sleep = _avg([s.sleep_score for s in prev_week_scores])
        health = HealthRepository(db)
        def _hours(d: date) -> float | None:
            night = health.sleep_for_day(d)
            return night.total_hours if night is not None else None

        this_hours = _avg([_hours(d) for d in _days(start, end)])
        prev_hours = _avg([_hours(d) for d in _days(prev_start, prev_end)])
        statement = f"Sleep score averaged {this_sleep:.0f} this week"
        if prev_sleep is not None:
            statement += f" (last week: {prev_sleep:.0f})"
        if this_hours is not None:
            statement += f", average {this_hours:.1f}h/night"
        statement += f". ({days_with_sleep} day(s) with data.)"
        facts.append(EvidenceFact(id="health:sleep", statement=statement))

    days_with_recovery = sum(1 for s in this_week_scores if s.recovery_score is not None)
    if days_with_recovery >= 3:
        this_rec = _avg([s.recovery_score for s in this_week_scores])
        prev_rec = _avg([s.recovery_score for s in prev_week_scores])
        statement = f"Recovery score averaged {this_rec:.0f} this week"
        if prev_rec is not None:
            statement += f" (last week: {prev_rec:.0f})"
        statement += "."
        facts.append(EvidenceFact(id="health:recovery", statement=statement))

    health = HealthRepository(db)
    steps = [sum(health.daily_values(d, HealthRecordType.STEPS)) or None for d in _days(start, end)]
    days_with_steps = sum(1 for v in steps if v is not None)
    if days_with_steps >= 3:
        avg_steps = _avg(steps)
        facts.append(
            EvidenceFact(
                id="health:activity",
                statement=f"Average {round(avg_steps):,} steps/day this week (walking and daily movement).",
            )
        )

    # Workouts over weeks, each with its duration and effort, so the mentor can
    # tell real exercise from token sessions and weigh a long gap properly.
    summary = workout_summary(health, today)
    if summary.last is not None or summary.workouts_synced:
        facts.append(EvidenceFact(id="health:workouts", statement=workout_statement(summary)))

    moods = MoodRepository(db)
    this_week_moods = moods.range(start, end)
    if len(this_week_moods) >= 3:
        prev_week_moods = moods.range(prev_start, prev_end)
        this_mood = _avg([m.mood for m in this_week_moods])
        this_stress = _avg([m.stress for m in this_week_moods])
        prev_mood = _avg([m.mood for m in prev_week_moods])
        statement = f"Average mood {this_mood:.1f}/5 and stress {this_stress:.1f}/5 this week ({len(this_week_moods)} check-in(s))"
        if prev_mood is not None:
            statement += f", vs mood {prev_mood:.1f}/5 last week"
        statement += "."
        facts.append(EvidenceFact(id="mind:pulse", statement=statement))

    github = GithubRepository(db)
    this_week_gh = github.range(start, end)
    active_days = sum(1 for g in this_week_gh if g.commits > 0)
    if len(this_week_gh) >= 3:
        commits = sum(g.commits for g in this_week_gh)
        facts.append(
            EvidenceFact(
                id="work:coding",
                statement=f"{commits} commit(s) across {active_days} active day(s) this week.",
            )
        )

    insights = InsightRepository(db)
    strong = sorted(
        (i for i in insights.all() if i.sample_size >= 14),
        key=lambda i: abs(i.correlation),
        reverse=True,
    )[:2]
    for i, insight in enumerate(strong):
        text = insight.narrative or insight.summary
        facts.append(
            EvidenceFact(id=f"link:{i}", statement=f"Pattern in your data: {text} (n={insight.sample_size} days).")
        )

    return facts


def _days(start: date, end: date) -> list[date]:
    out = []
    d = start
    while d <= end:
        out.append(d)
        d += timedelta(days=1)
    return out


# -- entrypoint ----------------------------------------------------------------------

_DROP_ORDER_PREFIXES = ("link:", "work:", "health:activity")


def _trim_to_budget(facts: list[EvidenceFact]) -> list[EvidenceFact]:
    if len(facts) <= _MAX_FACTS:
        return facts
    facts = list(facts)
    for prefix in _DROP_ORDER_PREFIXES:
        if len(facts) <= _MAX_FACTS:
            break
        facts = [f for f in facts if not f.id.startswith(prefix)]

    if len(facts) > _MAX_FACTS:
        cat_facts = [f for f in facts if f.id.startswith("cat:")]
        for f in cat_facts[6:]:
            if len(facts) <= _MAX_FACTS:
                break
            facts.remove(f)

    if len(facts) > _MAX_FACTS:
        big_facts = [f for f in facts if f.id.startswith("big:")]
        for f in big_facts[2:]:
            if len(facts) <= _MAX_FACTS:
                break
            facts.remove(f)

    return facts[:_MAX_FACTS]


def build_life_evidence(db: Session, today: date | None = None) -> list[EvidenceFact]:
    """Assemble the whole evidence bundle the mentor synthesizer is grounded in."""
    from datetime import datetime
    from zoneinfo import ZoneInfo

    today = today or datetime.now(ZoneInfo("Asia/Kolkata")).date()
    bounds = _month_bounds(today)
    categorize = build_categorizer(db)

    facts: list[EvidenceFact] = []
    builders = [
        lambda: _income_facts(db, categorize, today, bounds),
        lambda: _month_total_facts(db, categorize, bounds),
        lambda: _budget_facts(db, categorize, today),
        lambda: _fixed_commitment_facts(db, categorize, today, bounds),
        lambda: _card_facts(db, today),
        lambda: _trip_facts(db, categorize, today, bounds),
        lambda: _life_signal_facts(db, today),
    ]
    for builder in builders:
        try:
            facts.extend(builder())
        except Exception:
            logger.debug("life evidence builder failed", exc_info=True)

    try:
        category_facts, mtd_by_cat = _category_facts(db, categorize, bounds)
        facts.extend(category_facts)
        facts.extend(_comparison_facts(mtd_by_cat))
    except Exception:
        logger.debug("category evidence builder failed", exc_info=True)

    try:
        facts.extend(_big_purchase_facts(db, categorize, today, bounds))
    except Exception:
        logger.debug("big purchase evidence builder failed", exc_info=True)

    return _trim_to_budget(facts)
