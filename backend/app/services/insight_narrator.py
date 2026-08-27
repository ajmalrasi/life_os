"""LLM narration for discovered patterns.

The correlation engine (``services.correlation``) is the factual base: it decides
*which* relationships are real and computes the r / sample-size / uplift that
ground them. This module is a thin, best-effort layer on top that asks OpenClaw
to retell those same facts in fresher, more varied language so the Patterns
screen doesn't read like the same four templated sentences every run.

Design rules:
* **Grounded.** The model only ever rewords facts we already computed. It is told
  the exact r, n, lag and uplift and instructed not to invent numbers.
* **Best-effort.** If OpenClaw is unreachable, returns ``{}`` and callers keep the
  deterministic ``summary``. Narration must never break the nightly recompute.
* **Bounded latency.** One request narrates the whole batch; a short timeout keeps
  it off the critical path of an interactive recompute.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass

from app.config import get_settings
from app.services.openclaw import InferenceWorkload, OpenClawClient, OpenClawUnavailable

logger = logging.getLogger("lifeos.insight_narrator")
settings = get_settings()

# Keep narration off the critical path — if the model is slow we'd rather ship
# the deterministic summaries than stall the recompute response.
_NARRATION_TIMEOUT_SECONDS = 30.0

_SYSTEM_PROMPT = (
    "You are LifeOS, a personal-analytics companion. You are given a list of "
    "statistically discovered patterns in one user's own health, sleep, mood, "
    "stress, fitness, coding-execution and daily-spending data. Each pattern "
    "already has the math done for you: a correlation r, a sample size n, an "
    "optional day lag, and a factual summary.\n\n"
    "Your job: turn each pattern into ONE punchy, natural-language insight (max "
    "~22 words) that a person would actually want to read — the kind of "
    "observation a sharp coach who'd seen all their data would point out.\n\n"
    "RULES:\n"
    "- Ground every claim in the numbers provided. Never invent a metric, percentage "
    "or direction that isn't in the input.\n"
    "- Keep the real numbers honest: a low |r| or small n is a weak/emerging signal — "
    "hedge ('early sign', 'tends to') rather than overclaim.\n"
    "- Read direction and lag as behavior, not just stats: e.g. spending rising after "
    "high-stress days is stress-spending; a next-day lag means one thing drives the "
    "next. Name the human story, don't just restate r.\n"
    "- Money is behavioral too — frame spend patterns around what state precedes them "
    "or what they cost the user, not as accounting.\n"
    "- Vary your sentence openings and verbs across the list; do not start every line "
    "the same way.\n"
    "- Plain language, no markdown, no emojis, no bullet characters.\n"
    '- Return ONLY a JSON array of objects, one per pattern, each exactly '
    '{"id": "<the id you were given, copied verbatim>", "insight": "<your one-line '
    'insight for THAT pattern>"}. Echo every id unchanged and keep each insight with '
    "its own id — do not reorder, merge, add, or drop items. No prose, no code fences."
)


@dataclass
class InsightFact:
    """The factual record handed to the narrator for one discovered pattern."""

    driver: str
    outcome: str
    r: float
    n: int
    lag_days: int
    summary: str

    def key(self) -> str:
        return f"{self.driver}|{self.outcome}"


def narrate(facts: list[InsightFact], client: OpenClawClient | None = None) -> dict[str, str]:
    """Return ``{pair_key: narrative}`` for as many facts as the model narrates.

    Best-effort: returns an empty dict if narration is disabled, the model is
    unreachable, or the response can't be parsed. Partial results are honored —
    any fact the model skips simply keeps its deterministic summary downstream.
    """
    if not facts or not settings.insight_narration_enabled:
        return {}

    client = client or OpenClawClient(workload=InferenceWorkload.BACKGROUND)
    # Don't let a slow local model hold an interactive recompute hostage.
    try:
        client.timeout = min(client.timeout, _NARRATION_TIMEOUT_SECONDS)
    except Exception:  # client may be a stub in tests
        pass

    payload = [
        {
            "id": f.key(),
            "driver": f.driver,
            "outcome": f.outcome,
            "r": round(f.r, 3),
            "n": f.n,
            "lag_days": f.lag_days,
            "summary": f.summary,
        }
        for f in facts
    ]
    messages = [
        {"role": "system", "content": _SYSTEM_PROMPT},
        {
            "role": "user",
            "content": "PATTERNS (JSON):\n" + json.dumps(payload, indent=2),
        },
    ]

    try:
        # Higher temperature than the grounded chat path — we *want* varied
        # phrasing here, and the facts are fixed so there's nothing to hallucinate.
        raw = client.chat(messages, temperature=0.8)
    except OpenClawUnavailable as exc:
        logger.info("insight narration skipped (OpenClaw unavailable): %s", exc)
        return {}

    # Match by id, never by position: a reordered/short/over-long model response
    # must never staple one pattern's narrative onto a different pattern (that
    # would contradict the grounded summary the user sees). Unmatched facts keep
    # their deterministic summary downstream.
    by_id = _parse(raw)
    valid_keys = {f.key() for f in facts}
    out: dict[str, str] = {}
    for key, text in by_id.items():
        if key in valid_keys:
            text = (text or "").strip()
            if text:
                out[key] = text[:600]
    return out


def _parse(raw: str) -> dict[str, str]:
    """Extract ``{id: insight}`` from the model output, tolerantly.

    Accepts only ``[{"id": ..., "insight": ...}, ...]`` objects; anything that
    isn't a well-formed id/insight pair is ignored so a malformed item can't be
    misattributed to a pattern.
    """
    if not raw:
        return {}
    text = raw.strip()
    # Strip ``` fences if a chatty model added them despite instructions.
    if text.startswith("```"):
        text = text.strip("`")
        nl = text.find("\n")
        if nl != -1:
            text = text[nl + 1 :]
    # Isolate the array if the model wrapped it in stray prose.
    start, end = text.find("["), text.rfind("]")
    if start != -1 and end != -1 and end > start:
        text = text[start : end + 1]
    try:
        data = json.loads(text)
    except (ValueError, TypeError):
        return {}
    if not isinstance(data, list):
        return {}
    out: dict[str, str] = {}
    for item in data:
        if not isinstance(item, dict):
            continue
        key, insight = item.get("id"), item.get("insight")
        if isinstance(key, str) and isinstance(insight, str):
            out[key] = insight
    return out
