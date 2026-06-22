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
from app.services.openclaw import OpenClawClient, OpenClawUnavailable

logger = logging.getLogger("lifeos.insight_narrator")
settings = get_settings()

# Keep narration off the critical path — if the model is slow we'd rather ship
# the deterministic summaries than stall the recompute response.
_NARRATION_TIMEOUT_SECONDS = 30.0

_SYSTEM_PROMPT = (
    "You are LifeOS, a personal-analytics companion. You are given a list of "
    "statistically discovered patterns in one user's own health, mood, fitness and "
    "coding-execution data. Each pattern already has the math done for you: a "
    "correlation r, a sample size n, an optional day lag, and a factual summary.\n\n"
    "Your job: rewrite each pattern's summary as ONE punchy, natural-language "
    "insight (max ~22 words) that a person would actually want to read.\n\n"
    "RULES:\n"
    "- Ground every claim in the numbers provided. Never invent a metric, percentage "
    "or direction that isn't in the input.\n"
    "- Keep the real numbers honest: a low |r| or small n is a weak/emerging signal — "
    "hedge ('early sign', 'tends to') rather than overclaim.\n"
    "- Vary your sentence openings and verbs across the list; do not start every line "
    "the same way.\n"
    "- Plain language, no markdown, no emojis, no bullet characters.\n"
    "- Return ONLY a JSON array of strings, one per pattern, in the same order you "
    "received them. No prose, no keys, no code fences."
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

    client = client or OpenClawClient()
    # Don't let a slow local model hold an interactive recompute hostage.
    try:
        client.timeout = min(client.timeout, _NARRATION_TIMEOUT_SECONDS)
    except Exception:  # client may be a stub in tests
        pass

    payload = [
        {
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

    narratives = _parse(raw)
    if not narratives:
        return {}

    out: dict[str, str] = {}
    for fact, text in zip(facts, narratives):
        text = (text or "").strip()
        if text:
            out[fact.key()] = text[:600]
    return out


def _parse(raw: str) -> list[str]:
    """Extract a JSON array of strings from the model output, tolerantly."""
    if not raw:
        return []
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
        return []
    if not isinstance(data, list):
        return []
    return [str(item) for item in data]
