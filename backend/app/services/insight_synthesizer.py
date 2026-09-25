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
from dataclasses import dataclass

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
    "and work. Within health, weeks without a training workout outrank a day's "
    "HRV or sleep dip: say plainly how long it has been and push a restart. "
    "Walks are not training. Skip anything that is normal or minor. Fewer, sharper insights beat "
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
    "Return ONLY a JSON array, no prose, no code fences. Each item exactly:\n"
    "{\"title\": \"<=8 words\", \"verdict\": \"1-2 sentences\", \"action\": \"one sentence\", "
    "\"domain\": \"money|trip|health|mind|work|life\", \"severity\": \"high|medium|low\", "
    "\"cites\": [\"id\", ...]}"
)


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

    try:
        raw = client.chat(messages, temperature=0.5, max_tokens=900)
    except OpenClawUnavailable as exc:
        logger.info("insight synthesis skipped (OpenClaw unavailable): %s", exc)
        return []

    valid_ids = {f.id for f in evidence}
    drafts: list[dict] = []
    for item in _parse(raw):
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


def _parse(raw: str) -> list[dict]:
    """Extract a JSON array of insight objects, tolerantly."""
    if not raw:
        return []
    text = raw.strip()
    if text.startswith("```"):
        text = text.strip("`")
        nl = text.find("\n")
        if nl != -1:
            text = text[nl + 1 :]
    start, end = text.find("["), text.rfind("]")
    first_obj = text.find("{")
    # Only treat it as an array when the "[" opens before the first object;
    # otherwise the brackets belong to a nested "cites" list.
    if start != -1 and end > start and (first_obj == -1 or start < first_obj):
        try:
            data = json.loads(text[start : end + 1])
        except (ValueError, TypeError):
            data = None
        if isinstance(data, list):
            return [item for item in data if isinstance(item, dict)]
    # Qwen sometimes drops the outer brackets and emits a bare
    # comma-separated run of objects: decode each top-level object in turn.
    decoder = json.JSONDecoder()
    items: list[dict] = []
    pos = text.find("{")
    while pos != -1:
        try:
            obj, consumed = decoder.raw_decode(text, pos)
        except ValueError:
            pos = text.find("{", pos + 1)
            continue
        if isinstance(obj, dict):
            items.append(obj)
        pos = text.find("{", consumed)
    return items
