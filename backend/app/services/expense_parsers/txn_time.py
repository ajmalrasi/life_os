"""Extract the transaction's clock time from a bank alert email via the LLM.

Applied uniformly by the collector to *every* transaction-bearing email,
regardless of which parser produced the transaction. Banks vary: HDFC credit-
card and Axis UPI alerts state an explicit time in the body (``... at 14:41:02``,
``Date & Time: 29-06-26, 13:26:06 IST``), while HDFC RuPay UPI alerts carry only
a date. This returns the stated time when present; the collector falls back to
the email's arrival instant otherwise.

Time is cosmetic, so this never raises: an unreachable model, a malformed reply,
or an absent time all collapse to ``None``. It must not defer the email or block
the transaction the way a *field* extraction failure does.
"""

from __future__ import annotations

import json
import logging
import re
from datetime import time

from app.services.openclaw import OpenClawClient, OpenClawUnavailable

logger = logging.getLogger("lifeos.txn_time")

# Bank alert bodies are padded with long opaque tracking URLs that carry no
# signal but tokenize terribly; strip them and cap length so the prompt fits
# the shared model's small context window (kept self-contained here rather than
# reaching into llm_bank_parser, so time extraction stands on its own).
_MAX_PROMPT_CHARS = 2500
_URL_RE = re.compile(r"https?://\S+")


def _prepare_text(text: str) -> str:
    t = _URL_RE.sub(" ", text or "")
    t = re.sub(r"[ \t]+", " ", t)
    t = re.sub(r"\s*\n\s*", "\n", t)
    return t.strip()[:_MAX_PROMPT_CHARS]

_TIME_SCHEMA = {
    "type": "object",
    "properties": {
        "explicit_time_present": {"type": "boolean"},
        "time_text": {"type": "string"},
        "time_24h": {"type": "string"},
    },
    "required": ["explicit_time_present", "time_text", "time_24h"],
    "additionalProperties": False,
}

_TIME_PROMPT = (
    "You are given the plain-text body of a bank transaction alert email. "
    "Decide whether the body states an explicit CLOCK TIME of the transaction "
    "(hours:minutes, with or without AM/PM/seconds). A bare date with no time "
    "does NOT count. Return time_text exactly as written in the email, and "
    'time_24h as HH:MM in 24-hour form (or "" if there is no explicit time).\n\n'
    'Return ONLY {"explicit_time_present": true/false, "time_text": "...", '
    '"time_24h": "HH:MM"}.'
)

# HH:MM or HH:MM:SS, 24-hour.
_HHMM_RE = re.compile(r"^(\d{1,2}):(\d{2})(?::(\d{2}))?$")


def _parse_hhmm(raw: str) -> time | None:
    m = _HHMM_RE.match((raw or "").strip())
    if not m:
        return None
    hh, mm = int(m.group(1)), int(m.group(2))
    ss = int(m.group(3)) if m.group(3) else 0
    if not (0 <= hh <= 23 and 0 <= mm <= 59 and 0 <= ss <= 59):
        return None
    return time(hh, mm, ss)


def extract_clock_time(email_text: str, client: OpenClawClient) -> time | None:
    """The transaction's clock time stated in ``email_text``, or ``None``.

    ``None`` covers every failure mode (no time in the body, model unreachable,
    unparseable reply) — the caller treats them all the same and falls back to
    the email's arrival time.
    """
    text = _prepare_text(email_text)
    if not text:
        return None
    messages = [
        {"role": "system", "content": _TIME_PROMPT},
        {"role": "user", "content": "EMAIL:\n" + text},
    ]
    try:
        raw = client.chat_structured(messages, schema=_TIME_SCHEMA, schema_name="txn_time")
    except OpenClawUnavailable as exc:
        logger.info("txn_time extraction skipped (openclaw unavailable): %s", exc)
        return None
    try:
        data = json.loads(raw)
    except (ValueError, TypeError):
        logger.info("txn_time extraction skipped (unparseable response): %r", raw)
        return None
    if not data.get("explicit_time_present"):
        return None
    return _parse_hhmm(str(data.get("time_24h", "")))
