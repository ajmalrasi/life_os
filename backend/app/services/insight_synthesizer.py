"""LLM synthesis of higher-level, cross-domain insights.

The correlation engine finds pairwise relationships; the narrator rewords each
one. This module goes further: it hands the LLM the *whole* grounded picture —
every discovered correlation plus trends, averages and the current state — and
asks it to connect several facts into insights a person wouldn't spot from any
single number (e.g. how spending, stress and recovery move together).

The freedom is in which connections to draw and how to frame them; the grounding
is enforced structurally:

* **Evidence-bound.** The model only ever sees facts we already computed. It is
  told to build solely on them and to cite, by id, the facts each insight rests
  on. An insight that cites nothing in the evidence — i.e. an invented
  relationship — is dropped, never stored.
* **Best-effort.** Disabled, unreachable, or unparseable → returns ``[]`` and the
  caller keeps the previous batch. Synthesis must never break the nightly run.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass

from app.config import get_settings
from app.services.openclaw import OpenClawClient, OpenClawUnavailable

logger = logging.getLogger("lifeos.insight_synthesizer")
settings = get_settings()

# Keep synthesis off the critical path — one request, short-ish timeout.
_SYNTHESIS_TIMEOUT_SECONDS = 45.0
_MAX_INSIGHTS = 4

_SYSTEM_PROMPT = (
    "You are LifeOS, a sharp personal-analytics companion. You are given EVIDENCE: "
    "a list of factual, already-computed observations about ONE user's own data — "
    "correlations, trends, averages and current state across sleep, recovery, mood, "
    "stress, fitness, coding-execution and daily spending. Each fact has an \"id\".\n\n"
    "Your job: write 2-4 genuinely useful insights that CONNECT several facts into a "
    "story the user wouldn't notice from any single number. Favour cross-domain links "
    "(for example how spending, stress and recovery move together) over restating one "
    "correlation.\n\n"
    "STRICT RULES:\n"
    "- Build ONLY on the given facts. Every number, direction or trend you state must "
    "come from a fact you cite. Never invent a metric, correlation, percentage or "
    "relationship that is not in EVIDENCE.\n"
    "- Each insight MUST cite the ids of the facts it rests on (at least one, ideally "
    "two or more). If you cannot ground a claim in the evidence, do not make it.\n"
    "- Read direction and lag as behaviour, not statistics (e.g. spending rising after "
    "high-stress days is stress-spending).\n"
    "- Be honest about strength: a small sample (low n) or weak correlation is an early "
    "sign — hedge ('tends to', 'early sign') rather than overclaim.\n"
    "- Plain language, one or two sentences per insight, no markdown, no emojis.\n"
    "- Return ONLY a JSON array of objects, each exactly {\"title\": \"<=8 words\", "
    "\"insight\": \"the insight\", \"theme\": \"one word\", \"cites\": [\"id\", ...]}. "
    "No prose, no code fences."
)


@dataclass
class EvidenceFact:
    """One grounded, already-computed observation handed to the synthesizer."""

    id: str
    statement: str


def synthesize(evidence: list[EvidenceFact], client: OpenClawClient | None = None) -> list[dict]:
    """Return a list of grounded insight drafts, or ``[]`` best-effort.

    Each draft is ``{"title", "body", "theme", "cites"}`` where every id in
    ``cites`` is guaranteed to exist in ``evidence`` (invalid/ungrounded cites
    are stripped, and an insight left with no valid cite is dropped).
    """
    if not evidence or not settings.insight_synthesis_enabled:
        return []

    client = client or OpenClawClient()
    try:
        client.timeout = min(client.timeout, _SYNTHESIS_TIMEOUT_SECONDS)
    except Exception:  # client may be a stub in tests
        pass

    payload = [{"id": f.id, "fact": f.statement} for f in evidence]
    messages = [
        {"role": "system", "content": _SYSTEM_PROMPT},
        {"role": "user", "content": "EVIDENCE (JSON):\n" + json.dumps(payload, indent=2)},
    ]

    try:
        raw = client.chat(messages, temperature=0.6)
    except OpenClawUnavailable as exc:
        logger.info("insight synthesis skipped (OpenClaw unavailable): %s", exc)
        return []

    valid_ids = {f.id for f in evidence}
    drafts: list[dict] = []
    for item in _parse(raw):
        title = (item.get("title") or "").strip()
        body = (item.get("insight") or "").strip()
        cites_raw = item.get("cites")
        if not title or not body or not isinstance(cites_raw, list):
            continue
        # Ground the citation: keep only ids we actually handed the model. An
        # insight that cites nothing real is an invented relationship — drop it.
        cites = [c for c in cites_raw if isinstance(c, str) and c in valid_ids]
        if not cites:
            continue
        theme = item.get("theme")
        drafts.append({
            "title": title[:140],
            "body": body[:800],
            "theme": (str(theme).strip()[:48] if theme else None),
            "cites": cites,
        })
        if len(drafts) >= _MAX_INSIGHTS:
            break
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
    if start != -1 and end != -1 and end > start:
        text = text[start : end + 1]
    try:
        data = json.loads(text)
    except (ValueError, TypeError):
        return []
    if not isinstance(data, list):
        return []
    return [item for item in data if isinstance(item, dict)]
