"""Today brief: the backend computes the day's facts, Qwen judges the day.

This replaces the old rule-based state classifier (fuzzy thresholds that left
most days "unknown" and read missing data as zero). Code still does all the
arithmetic, as in ``insight_synthesizer``: every number the model may use is
stated in a fact here. The model picks the day's state, names what is missing,
and writes a headline, a short summary and one suggestion, under strict
JSON-schema decoding. Code then keeps only a reply whose citations are real
fact ids and whose summary numbers all appear in the facts. There is no canned
fallback; the last good brief stays until a new one passes those checks.
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from sqlalchemy.orm import Session

from app.config import get_settings
from app.models.daily_brief import DailyBrief
from app.models.daily_state import LifeState
from app.models.health import HealthRecordType as T
from app.repositories.analytics_repo import DailyBriefRepository, ScoreRepository, StateRepository
from app.repositories.github_repo import GithubRepository
from app.repositories.health_repo import HealthRepository
from app.repositories.mood_repo import MoodRepository
from app.services.insight_synthesizer import EvidenceFact
from app.services.openclaw import InferenceWorkload, OpenClawClient, OpenClawUnavailable

logger = logging.getLogger("lifeos.day_brief")
settings = get_settings()

IST = ZoneInfo("Asia/Kolkata")
_TIMEOUT_SECONDS = 45.0
_USUAL_WINDOW = 14
_WORKOUT_WINDOW = 28
_CONFIDENCE = {"low": 0.33, "medium": 0.66, "high": 1.0}

_SYSTEM_PROMPT = (
    "You are LifeOS writing the Today brief for ONE user. You are given FACTS about "
    "their day, each with an id. Every number has already been computed.\n\n"
    "Pick the state that best describes the day so far:\n"
    "- peak_performance: well recovered AND getting a lot done\n"
    "- execution_mode: getting a lot done without standout recovery\n"
    "- learning_mode: focused, moderate output (reading, exploring)\n"
    "- recovery_mode: the body is depleted (short or broken sleep, weak HRV/resting HR); rest is right\n"
    "- burnout_risk: strain sustained over several days\n"
    "- avoidance_mode: clearly rested and it is late enough in the day, yet almost nothing done\n"
    "- exploration_mode: varied, unstructured activity\n"
    "- unknown: not enough data yet to judge\n\n"
    "Rules:\n"
    "- Use the current time. Few steps or commits early in the day are normal, not avoidance.\n"
    "- Missing data is not zero. If last night's sleep or today's vitals have not arrived, say so.\n"
    "- A workout is deliberate training logged on the watch. Walking shows up only as steps; never call it a workout.\n"
    "- Use only numbers that appear in the facts. Do not calculate new ones.\n"
    "- The facts already say whether a reading is better or worse than usual. Repeat that; never reverse it.\n"
    "- Describe what the numbers show. Do not claim one thing caused another.\n"
    "- Keep the suggestion proportionate and practical; never tell the user to stop working.\n"
    "- No diagnoses, no markdown, no emoji.\n"
    "- headline: at most 8 words. summary: 1-2 sentences, at most 45 words. "
    "suggestion: one concrete thing for the rest of today, at most 20 words.\n"
    "- confidence: how complete the data is for judging today (low, medium, high).\n"
    "- cites: the ids of the facts you relied on."
)


def _local(dt: datetime) -> datetime:
    return dt.replace(tzinfo=IST) if dt.tzinfo is None else dt.astimezone(IST)


def _when(d: date, today: date) -> str:
    if d == today:
        return "today"
    if d == today - timedelta(days=1):
        return "yesterday"
    return d.strftime("%b %d").replace(" 0", " ")


def _day_total(health: HealthRepository, d: date, t: T) -> float | None:
    vals = health.daily_values(d, t)
    return sum(vals) if vals else None


def _day_mean(health: HealthRepository, d: date, t: T) -> float | None:
    vals = health.daily_values(d, t)
    return sum(vals) / len(vals) if vals else None


def _avg(values: list[float | None]) -> float | None:
    vals = [v for v in values if v is not None]
    return sum(vals) / len(vals) if vals else None


def _versus(value: float, usual: float, good_when: str) -> str:
    """Say which way a reading sits against its usual, so the model never has to
    work out the direction (it once called a lower resting HR "elevated")."""
    if usual <= 0 or abs(value - usual) / usual < 0.05:
        return "about your usual"
    higher = value > usual
    side = "above" if higher else "below"
    better = (higher and good_when == "higher") or (not higher and good_when == "lower")
    return f"{side} your usual, which is {'better' if better else 'worse'}"


def _time_of_day(now: datetime) -> str:
    if now.hour < 12:
        return "morning"
    if now.hour < 17:
        return "afternoon"
    return "evening"


def is_walk_workout(label: str | None) -> bool:
    """A walk or hike logged as a workout is still walking, not training."""
    return bool(label) and any(word in label.lower() for word in ("walk", "hik"))


def _workout_fact(health: HealthRepository, day: date) -> str:
    """Recent training, told apart from walking, and honest about missing exports."""
    window_start = day - timedelta(days=_WORKOUT_WINDOW - 1)
    history = health.workouts_between(date(1970, 1, 1), day)
    training = [w for w in history if not is_walk_workout(w.label)]
    recent = [w for w in training if _local(w.start_time).date() >= window_start]
    walks = [w for w in history if is_walk_workout(w.label) and _local(w.start_time).date() >= window_start]
    parts: list[str] = []
    if recent:
        minutes = sum(w.value for w in recent)
        kinds = ", ".join(dict.fromkeys(w.label or "Workout" for w in recent))
        parts.append(
            f"Training workouts in the last {_WORKOUT_WINDOW} days: {len(recent)}, {minutes:.0f} min in total ({kinds})."
        )
    if training:
        last = training[-1]
        d = _local(last.start_time).date()
        ago = (day - d).days
        when = _when(d, day) if ago <= 1 else f"{_when(d, day)}, {ago} days ago"
        parts.append(f"Most recent training workout: {last.label or 'Workout'}, {last.value:.0f} min ({when}).")
    else:
        parts.append("No training workout has ever been recorded.")
    if walks:
        parts.append(
            f"Walks logged as workouts in the last {_WORKOUT_WINDOW} days: {len(walks)}, "
            f"{sum(w.value for w in walks):.0f} min; these are walking, not training."
        )
    if health.workout_export_seen_at() is None:
        parts.append(
            "The phone does not send workouts yet, so workouts after the Apple Health history "
            "import are unknown, not zero."
        )
    elif not recent:
        parts.append(f"No training workouts in the last {_WORKOUT_WINDOW} days.")
    return " ".join(parts)


def compute_day_facts(db: Session, day: date, now: datetime | None = None) -> list[EvidenceFact]:
    """Every number the brief may use, stated plainly. Deterministic and cheap."""
    now = _local(now or datetime.now(IST))
    health = HealthRepository(db)
    facts: list[EvidenceFact] = [
        EvidenceFact("now", f"Current local time is {now:%H:%M} on {day:%A}, {_time_of_day(now)}."),
    ]

    # --- sleep: filed under the morning you woke up ---
    night = health.sleep_for_day(day)
    if night is not None:
        parts = [f"Last night: slept {night.total_hours:.1f} h, {_local(night.start):%H:%M} to {_local(night.end):%H:%M}"]
        stages = [
            f"{label} {hours:.1f} h"
            for label, hours in (("deep", night.deep_hours), ("REM", night.rem_hours), ("awake", night.awake_hours))
            if hours is not None
        ]
        if stages:
            parts.append(", ".join(stages))
        facts.append(EvidenceFact("sleep_last_night", "; ".join(parts) + "."))
    else:
        text = "No sleep has been recorded for last night yet; this is missing data, not a bad night."
        for back in range(1, _USUAL_WINDOW + 1):
            earlier = health.sleep_for_day(day - timedelta(days=back))
            if earlier is not None:
                text += f" The most recent recorded night ended {_when(day - timedelta(days=back), day)} ({earlier.total_hours:.1f} h)."
                break
        facts.append(EvidenceFact("sleep_last_night", text))

    usual_nights = [health.sleep_for_day(day - timedelta(days=i)) for i in range(1, _USUAL_WINDOW + 1)]
    usual_hours = [n.total_hours for n in usual_nights if n is not None]
    if len(usual_hours) >= 3:
        usual = sum(usual_hours) / len(usual_hours)
        text = f"Usual sleep: {usual:.1f} h a night over the {len(usual_hours)} recorded nights of the past {_USUAL_WINDOW} days"
        if night is not None:
            text += f"; last night was {_versus(night.total_hours, usual, 'higher')}"
        facts.append(EvidenceFact("sleep_usual", text + "."))

    # --- recovery vitals: the latest daily value, with its date ---
    for fid, label, unit, t, good_when in (
        ("hrv", "HRV", "ms", T.HRV, "higher"),
        ("resting_hr", "Resting heart rate", "bpm", T.RESTING_HEART_RATE, "lower"),
    ):
        for back in range(0, 4):
            d = day - timedelta(days=back)
            value = _day_mean(health, d, t)
            if value is None:
                continue
            baseline = health.baseline_mean(d - timedelta(days=30), d, t)
            text = f"{label} {value:.0f} {unit} ({_when(d, day)})"
            if baseline is not None:
                text += f"; 30-day usual {baseline:.0f} {unit}, so {_versus(value, baseline, good_when)}"
            facts.append(EvidenceFact(fid, text + "."))
            break
        else:
            facts.append(EvidenceFact(fid, f"No {label} reading in the last 4 days."))

    # --- activity so far today ---
    steps = _day_total(health, day, T.STEPS)
    active_kcal = _day_total(health, day, T.CALORIES)
    workout = _day_total(health, day, T.WORKOUT)
    if steps is None and active_kcal is None and workout is None:
        facts.append(EvidenceFact("activity_today", "No activity data has arrived for today yet."))
    else:
        bits = []
        if steps is not None:
            bits.append(f"{steps:,.0f} steps")
        if active_kcal is not None:
            bits.append(f"{active_kcal:,.0f} kcal active energy")
        if workout is not None:
            bits.append(f"{workout:.0f} min of workouts")
        facts.append(EvidenceFact("activity_today", "So far today: " + ", ".join(bits) + "."))
    usual_steps = _avg([_day_total(health, day - timedelta(days=i), T.STEPS) for i in range(1, _USUAL_WINDOW + 1)])
    if usual_steps is not None:
        facts.append(EvidenceFact(
            "steps_usual",
            f"Usual full-day steps: {usual_steps:,.0f} (today's count is partial until the day ends).",
        ))

    # --- training: workouts, as opposed to walking ---
    facts.append(EvidenceFact("workouts", _workout_fact(health, day)))

    # --- work output ---
    github = GithubRepository(db)
    for fid, d in (("code_today", day), ("code_yesterday", day - timedelta(days=1))):
        gh = github.get(d)
        when = _when(d, day)
        if gh is None or (gh.commits == 0 and gh.coding_minutes == 0):
            facts.append(EvidenceFact(fid, f"No commits or coding time recorded {when}."))
        else:
            facts.append(EvidenceFact(
                fid,
                f"Code {when}: {gh.commits} commits, {gh.coding_minutes} min of coding across {gh.active_repos} repos.",
            ))

    # --- self-report ---
    mood = MoodRepository(db).get(day)
    if mood is None:
        facts.append(EvidenceFact("pulse", "No Pulse check-in (mood, energy, focus, stress) today."))
    else:
        fields = [
            f"{name} {v}/5"
            for name, v in (("mood", mood.mood), ("energy", mood.energy), ("focus", mood.focus), ("stress", mood.stress))
            if v is not None
        ]
        facts.append(EvidenceFact("pulse", "Pulse check-in today: " + ", ".join(fields) + "."))

    # --- the scores shown next to this brief, so the two never disagree ---
    score = ScoreRepository(db).get(day)
    if score is not None:
        shown = [
            f"{name} {v:.0f}"
            for name, v in (
                ("sleep", score.sleep_score), ("recovery", score.recovery_score),
                ("execution", score.execution_score), ("fitness", score.fitness_score),
                ("wellness", score.wellness_score), ("burnout risk", score.burnout_risk),
            )
            if v is not None
        ]
        if shown:
            facts.append(EvidenceFact("scores_today", "Today's 0-100 scores: " + ", ".join(shown) + "."))

    last = health.last_received_at()
    if last is not None:
        last_local = _local(last)
        facts.append(EvidenceFact(
            "health_freshness",
            f"Apple Health data last arrived at {last_local:%H:%M} {_when(last_local.date(), day)}.",
        ))
    return facts


def facts_hash(facts: list[EvidenceFact]) -> str:
    """Changes when any fact changes. The clock only counts by part of the day,
    and arrival time not at all: a push that changes nothing needs no new brief."""
    material = []
    for f in facts:
        if f.id == "health_freshness":
            continue
        material.append(f.statement.split(" on ")[-1] if f.id == "now" else f.statement)
    return hashlib.sha256("\n".join(material).encode()).hexdigest()


def _schema(fact_ids: list[str]) -> dict:
    return {
        "type": "object",
        "properties": {
            "state": {"type": "string", "enum": [s.value for s in LifeState]},
            "confidence": {"type": "string", "enum": list(_CONFIDENCE)},
            "headline": {"type": "string"},
            "summary": {"type": "string"},
            "suggestion": {"type": "string"},
            "cites": {"type": "array", "items": {"type": "string", "enum": fact_ids}},
        },
        "required": ["state", "confidence", "headline", "summary", "suggestion", "cites"],
        "additionalProperties": False,
    }


_NUMBER = re.compile(r"\d[\d,]*(?:\.\d+)?")


def _numbers(text: str) -> set[str]:
    return {m.group().replace(",", "").rstrip(".") for m in _NUMBER.finditer(text)}


def validate_brief(reply: dict, facts: list[EvidenceFact]) -> dict | None:
    """Keep a reply only if it is complete, cites real facts, and invents no numbers."""
    ids = {f.id for f in facts}
    try:
        state = LifeState(reply["state"])
        confidence = str(reply["confidence"])
        headline = str(reply["headline"]).strip()
        summary = str(reply["summary"]).strip()
        suggestion = str(reply["suggestion"]).strip()
        cites = [str(c) for c in reply["cites"]]
    except (KeyError, TypeError, ValueError):
        return None
    if confidence not in _CONFIDENCE or not (headline and summary and suggestion):
        return None
    if not cites or not set(cites) <= ids:
        return None
    known = set()
    for f in facts:
        known |= _numbers(f.statement)
    invented = _numbers(headline + " " + summary) - known
    if invented:
        logger.info("day brief rejected: numbers not in facts %s", sorted(invented))
        return None
    return {
        "state": state.value,
        "confidence": confidence,
        "headline": headline[:160],
        "summary": summary[:600],
        "suggestion": suggestion[:300],
        "cites": ",".join(dict.fromkeys(cites))[:500],
    }


def generate_brief(
    db: Session,
    day: date,
    client: OpenClawClient | None = None,
    now: datetime | None = None,
) -> DailyBrief | None:
    """Write a new brief if the facts changed. Returns the stored brief (new or
    unchanged), or None when there is none and the model could not produce one."""
    repo = DailyBriefRepository(db)
    facts = compute_day_facts(db, day, now=now)
    digest = facts_hash(facts)
    current = repo.get(day)
    if current is not None and current.facts_hash == digest:
        return current
    if not settings.insight_synthesis_enabled:
        return current

    client = client or OpenClawClient(workload=InferenceWorkload.BACKGROUND)
    try:
        client.timeout = min(client.timeout, _TIMEOUT_SECONDS)
    except Exception:  # client may be a stub in tests
        pass
    payload = [{"id": f.id, "fact": f.statement} for f in facts]
    messages = [
        {"role": "system", "content": _SYSTEM_PROMPT},
        {"role": "user", "content": "FACTS (JSON):\n" + json.dumps(payload, indent=2)},
    ]
    try:
        reply = client.chat_json(
            messages, _schema([f.id for f in facts]), "today_brief", max_tokens=320, temperature=0.3,
        )
    except OpenClawUnavailable as exc:
        logger.info("day brief skipped (model unavailable): %s", exc)
        return current
    except Exception:
        logger.warning("day brief generation failed", exc_info=True)
        return current

    brief = validate_brief(reply, facts)
    if brief is None:
        return current
    row = repo.upsert(day, facts=json.dumps(payload), facts_hash=digest, **brief)
    # Ask and notifications read the day's state from daily_states.
    StateRepository(db).upsert(day, LifeState(brief["state"]), _CONFIDENCE[brief["confidence"]], brief["summary"][:500])
    return row


def is_current(db: Session, day: date, brief: DailyBrief | None, now: datetime | None = None) -> bool:
    return brief is not None and brief.facts_hash == facts_hash(compute_day_facts(db, day, now=now))
