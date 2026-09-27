"""LLM synthesis of the mentor-style, cross-domain Insights page.

``app.services.life_evidence`` computes every number this module can cite —
money first (income, budgets, card bills, recurring charges, categories),
then trips, sleep, mood and work. This module's only job is judgement and
phrasing: pick the 3-5 things that matter most and write one candid,
second-person verdict + action for each.

The freedom is in which facts to prioritize and how to phrase the verdict;
the grounding is enforced structurally:

* **Evidence-bound.** The model only ever sees facts we already computed. It
  is told to build solely on them and to cite, by id, the facts each insight
  rests on. An insight that cites nothing in the evidence — i.e. an invented
  number or relationship — is dropped, never stored.
* **Best-effort.** Disabled, unreachable, or unparseable → returns ``[]`` and
  the caller keeps the previous batch. Synthesis must never break the nightly
  run or leave the user with no insights at all.
"""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass

from app.services.training import OPEN_GAP_MARKER, WORKOUT_JUDGEMENT_RULE
from app.config import get_settings
from app.services.openclaw import InferenceWorkload, OpenClawClient, OpenClawUnavailable

logger = logging.getLogger("lifeos.insight_synthesizer")
settings = get_settings()

# Keep synthesis off the critical path — one request, short-ish timeout.
_SYNTHESIS_TIMEOUT_SECONDS = 45.0
_MAX_INSIGHTS = 5

_DOMAINS = {"money", "trip", "health", "mind", "work", "life"}
_SEVERITIES = {"high", "medium", "low"}
_SEVERITY_ORDER = {"high": 0, "medium": 1, "low": 2}

_SYSTEM_PROMPT = (
    "You are the user's personal mentor inside LifeOS: a sharp, honest third person "
    "who looks at their real numbers and tells them what they need to hear. You are "
    "given EVIDENCE: facts already computed from ONE user's own data (money, "
    "budgets, card bills, recurring charges, trips, sleep, activity, mood, work). "
    "Each fact has an \"id\". Money is Indian rupees.\n\n"
    "Pick the 3 to 5 things that matter MOST right now and write one insight for "
    "each. Prioritise: money problems first (overspending against income or "
    "budget, a category far above its usual level, a big purchase, a costly "
    "comparison between categories, bills due soon), then trips, then health, mood "
    "and work. " + WORKOUT_JUDGEMENT_RULE + " If workouts "
    "have a current open gap, say plainly for how long and push a restart. Skip anything that is normal or minor. Fewer, sharper insights beat "
    "more.\n\n"
    "For each insight:\n"
    "- Give a clear judgement. Say plainly when something is too much, a bad "
    "decision, or a habit to fix, and say so when something is going well.\n"
    "- Use the exact numbers from the facts you cite. Never invent or recompute a "
    "number, and never state a number that is not in a cited fact.\n"
    "- Give one concrete, doable action (cut, cap, pause, move to savings, set a "
    "budget, sleep earlier, etc.).\n"
    "- Treat patterns as associations, not causes.\n"
    "- Only claim what the cited facts say. Do not invent debts, causes or\n"
    "  diagnoses, and keep a trip's timing exactly as its fact states\n"
    "  (upcoming, active or ended). If a fact gives both a score and hours,\n"
    "  judge using both.\n"
    "- Do not recommend specific investments, funds, stocks or financial products.\n"
    "- EMI facts are fixed commitments; do not call them impulsive spending.\n"
    "- If monthly income is missing you may say, once, that setting it in Money "
    "settings would allow judging spending against income.\n"
    "- Plain language, no markdown, no emojis. Talk to the user as \"you\".\n\n"
    "Return {\"insights\": [...]} where each item is: title (8 words or fewer), "
    "verdict (1-2 sentences), action (one sentence), domain, severity, and cites "
    "(the ids of the facts it uses)."
)


def _schema(fact_ids: list[str]) -> dict:
    """Guided-decoding schema: bounded lengths so a reply cannot loop to the
    token limit (it once repeated "then a 1-day gap" until it timed out), and
    cites limited to the facts actually given."""
    item = {
        "type": "object",
        "properties": {
            "title": {"type": "string", "maxLength": 80},
            "verdict": {"type": "string", "maxLength": 320},
            "action": {"type": "string", "maxLength": 200},
            "domain": {"type": "string", "enum": sorted(_DOMAINS)},
            "severity": {"type": "string", "enum": ["high", "medium", "low"]},
            "cites": {
                "type": "array",
                "items": {"type": "string", "enum": fact_ids},
                "minItems": 1,
                "maxItems": 4,
            },
        },
        "required": ["title", "verdict", "action", "domain", "severity", "cites"],
        "additionalProperties": False,
    }
    return {
        "type": "object",
        "properties": {"insights": {"type": "array", "items": item, "maxItems": _MAX_INSIGHTS}},
        "required": ["insights"],
        "additionalProperties": False,
    }


# Words that call the workout routine lapsed or stopped. Only true while the
# workouts fact states an open gap; a gap that has ended is history.
_LAPSE_CLAIM = re.compile(
    r"\b(laps\w*|brok\w*|break\w*|stopped|stalled|abandon\w*|inactiv\w*|"
    r"(open|current|ongoing) gap|no (recent )?workouts?)\b",
    re.IGNORECASE,
)
_DAYS_AGO = re.compile(r"(\d+)[ -]days? ago", re.IGNORECASE)
_LAST_WORKOUT = re.compile(r"most recent workout was (\d+) days? ago")


@dataclass
class EvidenceFact:
    """One grounded, already-computed observation handed to the synthesizer."""

    id: str
    statement: str


def synthesize(
    evidence: list[EvidenceFact],
    client: OpenClawClient | None = None,
    today_label: str | None = None,
) -> list[dict]:
    """Return 0-5 grounded mentor insights, best-effort.

    Each draft is ``{"title", "body", "action", "theme", "severity", "cites"}``
    (``theme`` carries the domain; ``body`` carries the verdict — same field
    names the repository/model already use) where every id in ``cites`` is
    guaranteed to exist in ``evidence`` (invalid/ungrounded cites are
    stripped, and an insight left with no valid cite is dropped). Does not
    pad to a fixed count: returns however many (3-5, occasionally fewer) the
    model produced.
    """
    if not evidence or not settings.insight_synthesis_enabled:
        return []

    client = client or OpenClawClient(workload=InferenceWorkload.BACKGROUND)
    try:
        client.timeout = min(client.timeout, _SYNTHESIS_TIMEOUT_SECONDS)
    except Exception:  # client may be a stub in tests
        pass

    payload = [{"id": f.id, "fact": f.statement} for f in evidence]
    user_content = ""
    if today_label:
        user_content += today_label + "\n"
    user_content += "EVIDENCE (JSON):\n" + json.dumps(payload, ensure_ascii=False)
    messages = [
        {"role": "system", "content": _SYSTEM_PROMPT},
        {"role": "user", "content": user_content},
    ]

    fact_ids = [f.id for f in evidence]
    try:
        reply = client.chat_json(
            messages, schema=_schema(fact_ids), schema_name="mentor_insights",
            max_tokens=900, temperature=0.5,
        )
    except OpenClawUnavailable as exc:
        logger.info("insight synthesis skipped (OpenClaw unavailable): %s", exc)
        return []
    items = reply.get("insights") if isinstance(reply, dict) else None
    workouts_fact = next((f.statement for f in evidence if f.id == "health:workouts"), "")
    open_gap = OPEN_GAP_MARKER in workouts_fact
    last = _LAST_WORKOUT.search(workouts_fact)
    days_since_last = last.group(1) if last else None

    valid_ids = {f.id for f in evidence}
    known_numbers: set[str] = set()
    for f in evidence:
        known_numbers |= _numbers(f.statement)
    drafts: list[dict] = []
    for item in items if isinstance(items, list) else []:
        if not isinstance(item, dict):
            continue
        title = (item.get("title") or "").strip()
        body = (item.get("verdict") or "").strip()
        cites_raw = item.get("cites")
        if not title or not body or not isinstance(cites_raw, list):
            continue
        # Ground the citation: keep only ids we actually handed the model. An
        # insight that cites nothing real is an invented judgement — drop it.
        cites = [c for c in cites_raw if isinstance(c, str) and c in valid_ids]
        if not cites:
            continue
        # A number the facts never stated is an invented or recomputed one
        # (it once wrote "lapsed for 50 days" from a fact saying 58). The
        # action may set a new target, so only the judgement is checked.
        invented = _numbers(title + " " + body) - known_numbers
        if invented:
            logger.info("insight dropped: numbers not in evidence %s", sorted(invented))
            continue

        if "health:workouts" in cites:
            if not open_gap and _LAPSE_CLAIM.search(title + " " + body):
                logger.info("insight dropped: calls workouts lapsed without an open gap")
                continue
            # "N days ago" must be the last workout's real age (it once took 13
            # from "the last 13 weeks" when the last workout was yesterday).
            ages = set(_DAYS_AGO.findall(title + " " + body))
            if days_since_last is not None and ages - {days_since_last}:
                logger.info("insight dropped: last workout age %s, fact says %s", sorted(ages), days_since_last)
                continue

        action = item.get("action")
        domain = str(item.get("domain") or "life").strip().lower()
        if domain not in _DOMAINS:
            domain = "life"
        severity = str(item.get("severity") or "medium").strip().lower()
        if severity not in _SEVERITIES:
            severity = "medium"

        drafts.append({
            "title": title[:140],
            "body": body[:800],
            "action": (str(action).strip()[:300] if action else None),
            "theme": domain,
            "severity": severity,
            "cites": cites,
        })
        if len(drafts) >= _MAX_INSIGHTS:
            break

    drafts.sort(key=lambda d: _SEVERITY_ORDER.get(d["severity"], 1))
    return drafts


_NUMBER = re.compile(r"\d[\d,]*(?:\.\d+)?")


def _numbers(text: str) -> set[str]:
    return {m.group().replace(",", "").rstrip(".") for m in _NUMBER.finditer(text)}
