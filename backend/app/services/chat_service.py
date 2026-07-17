"""LifeOS chat service.

Personal-data questions receive a compact factual snapshot and strict citation
rules. General questions take a separate path with no LifeOS snapshot, so a
request such as "write Fibonacci code" cannot be redirected into sleep advice.
"""

from __future__ import annotations

import json
import logging
import re
from datetime import date, timedelta
from time import monotonic

from sqlalchemy.orm import Session

from app.config import get_settings
from app.repositories.analytics_repo import (
    InsightRepository,
    ScoreRepository,
    StateRepository,
    SynthesizedInsightRepository,
)
from app.repositories.expense_repo import ExpenseRepository
from app.repositories.github_repo import GithubRepository
from app.repositories.mood_repo import MoodRepository
from app.schemas.chat import ChatRequest, ChatResponse, Citation
from app.services import agent_tools
from app.services.agent_tools import AgentTools
from app.services.openclaw import OpenClawClient, OpenClawTimeout, OpenClawUnavailable

logger = logging.getLogger(__name__)
settings = get_settings()

# Max tool-call round trips before we force a final answer. Keeps the agent loop
# bounded so a misbehaving model can't spin forever.
MAX_AGENT_STEPS = 2

# Shown before there's enough data to tailor questions, and whenever the LLM is
# unreachable. Kept generic but useful.
DEFAULT_SUGGESTIONS = [
    "How am I doing this week?",
    "Do I get more done on days I work out?",
    "Am I moving toward burnout?",
    "What should I focus on today?",
]

SYSTEM_PROMPT = (
    "You are LifeOS, a personal intelligence companion. You analyze one user's own "
    "health, mood, project-execution and daily-spending data to explain — in plain "
    "language — why they perform well, procrastinate, drift toward burnout, or spend "
    "the way they do, and what to do next. The DATA CONTEXT includes a 'spending' "
    "block (total, daily average, and recent daily spend) when expense data exists; "
    "treat those amounts as the user's local currency and cite them like any other "
    "metric.\n\n"
    "HOW YOU WORK\n"
    "- Each turn begins with a DATA CONTEXT snapshot (JSON). It is your primary source "
    "of truth.\n"
    "- When you need more than it contains — older days, raw metrics, or commit "
    "detail — call the tools (git_log, github_events, health_summary) before "
    "answering. Don't guess when a tool can tell you.\n"
    "- For 'right now', 'today', or 'last night' health questions — or when stored "
    "data is too stale — call live_health to pull live readings straight from the "
    "user's iPhone. If it returns an error that the server is unreachable, relay "
    "that fix to the user instead of guessing.\n\n"
    "GROUNDING RULES (non-negotiable)\n"
    "1. Use ONLY the DATA CONTEXT and tool results. Never invent, estimate, or "
    "round-trip numbers that aren't there.\n"
    "2. Cite a specific metric for every quantitative claim, e.g. '(avg sleep score "
    "78 over 30d)' or '(recovery 52 on 2026-06-14)'.\n"
    "3. A metric that is null, missing, or has zero days of data is UNKNOWN — not "
    "zero and not bad. Say it's unavailable, and if it matters to the question, tell "
    "the user how to fill the gap: sleep and recovery need Apple Health arriving "
    "through Health Auto Export; wellness needs a Pulse check-in; execution needs GitHub "
    "activity.\n"
    "4. Read correlations honestly: |r| >= 0.5 is strong, 0.3-0.5 moderate, < 0.3 "
    "weak. Flag a small sample size (n) instead of overclaiming, and never present "
    "correlation as proven cause.\n\n"
    "STYLE\n"
    "- Concise and direct. Lead with the answer, then the supporting evidence.\n"
    "- Keep the final answer under 220 words and always finish the last sentence.\n"
    "- Prefer specific, actionable next steps over generic wellness advice.\n"
    "- You are not a clinician: for medical concerns, suggest seeing a professional "
    "rather than interpreting symptoms.\n"
    "- If the data is too sparse to answer, say so plainly and name exactly what to "
    "sync or log to get a real answer."
)

GENERAL_SYSTEM_PROMPT = (
    "You are the local assistant inside LifeOS. Answer the user's current request "
    "directly, including general knowledge, explanations, and programming tasks. "
    "Do not mention or infer the user's sleep, health, mood, spending, productivity, "
    "or other personal LifeOS data unless the current request explicitly asks for it. "
    "Prioritize the newest request over an older conversation topic. Be concise, "
    "accurate, and provide complete runnable code when code is requested."
)

_PERSONAL_DATA_TERMS = re.compile(
    r"\b(?:sleep|recovery|heart\s*rate|hrv|spo2|steps?|workouts?|exercise|fitness|"
    r"mood|stress|energy|focus|burnout|wellness|health|score|state|spend(?:ing)?|"
    r"expenses?|transactions?|bank|cards?|emi|commits?|github|execution|"
    r"productiv(?:e|ity)|unproductive|procrastinat(?:e|ing|ion)|tired|fatigue|"
    r"performance|pulse|vitals?)\b",
    re.IGNORECASE,
)
_PERSONAL_CUES = re.compile(
    r"\b(?:my|me|mine|i|today|yesterday|tonight|last\s+night|this\s+week|"
    r"this\s+month|recent(?:ly)?)\b",
    re.IGNORECASE,
)
_EXPLICIT_PERSONAL_REQUEST = re.compile(
    r"\b(?:lifeos|my\s+data|according\s+to\s+my|how\s+am\s+i\s+doing|"
    r"what\s+should\s+i\s+(?:do|focus\s+on)\s+today)\b",
    re.IGNORECASE,
)
_SHORT_FOLLOW_UP = re.compile(
    r"^(?:why|how|what\s+about|and|but|can\s+you\s+explain|tell\s+me\s+more)\b",
    re.IGNORECASE,
)


def _is_personal_data_question(message: str, history=()) -> bool:
    """Route only questions that actually ask about the user's LifeOS data.

    Domain words alone are insufficient: "what is REM sleep?" is a general
    knowledge question, while "how was my REM sleep?" is personal. A short
    follow-up inherits the most recent user's route so "why?" still works.
    """
    text = message.strip()
    if _EXPLICIT_PERSONAL_REQUEST.search(text):
        return True
    if _PERSONAL_DATA_TERMS.search(text) and _PERSONAL_CUES.search(text):
        return True
    if len(text.split()) <= 8 and _SHORT_FOLLOW_UP.search(text):
        for previous in reversed(history):
            if getattr(previous, "role", "") == "user":
                return _is_personal_data_question(getattr(previous, "content", ""))
    return False


class ChatService:
    def __init__(self, db: Session, client: OpenClawClient | None = None) -> None:
        self.db = db
        self.client = client or OpenClawClient()
        self.scores = ScoreRepository(db)
        self.states = StateRepository(db)
        self.mood = MoodRepository(db)
        self.github = GithubRepository(db)
        self.insights = InsightRepository(db)
        self.synth = SynthesizedInsightRepository(db)
        self.expenses = ExpenseRepository(db)

    def build_context(self, days: int) -> dict:
        end = date.today()
        start = end - timedelta(days=days - 1)

        score_rows = self.scores.range(start, end)
        state_rows = self.states.range(start, end)
        mood_rows = self.mood.range(start, end)
        gh_rows = self.github.range(start, end)
        insight_rows = self.insights.all()
        synth_rows = self.synth.all()
        spend_map = self.expenses.daily_spend(start, end)

        def avg(attr: str, rows) -> float | None:
            vals = [getattr(r, attr) for r in rows if getattr(r, attr) is not None]
            return round(sum(vals) / len(vals), 1) if vals else None

        latest_state = state_rows[-1] if state_rows else None

        return {
            "window": {"start": start.isoformat(), "end": end.isoformat(), "days": days},
            "averages": {
                "sleep_score": avg("sleep_score", score_rows),
                "recovery_score": avg("recovery_score", score_rows),
                "execution_score": avg("execution_score", score_rows),
                "wellness_score": avg("wellness_score", score_rows),
                "fitness_score": avg("fitness_score", score_rows),
                "consistency_score": avg("consistency_score", score_rows),
                "burnout_risk": avg("burnout_risk", score_rows),
                "mood": avg("mood", mood_rows),
                "focus": avg("focus", mood_rows),
                "stress": avg("stress", mood_rows),
            },
            "current_state": (
                {
                    "day": latest_state.day.isoformat(),
                    "state": latest_state.state.value,
                    "confidence": latest_state.confidence,
                    "rationale": latest_state.rationale,
                }
                if latest_state
                else None
            ),
            "totals": {
                "commits": sum(g.commits for g in gh_rows),
                "coding_minutes": sum(g.coding_minutes for g in gh_rows),
                "days_with_mood_checkin": len(mood_rows),
                "days_with_scores": len(score_rows),
            },
            "spending": (
                {
                    "total_spend": round(sum(spend_map.values())),
                    "avg_daily_spend": round(sum(spend_map.values()) / len(spend_map), 1),
                    "days_with_spend": len(spend_map),
                    "recent_daily_spend": [
                        {"day": d.isoformat(), "spend": round(spend_map[d])}
                        for d in sorted(spend_map)[-14:]
                    ],
                }
                if spend_map
                else None
            ),
            "discovered_patterns": [
                {"summary": i.summary, "r": i.correlation, "n": i.sample_size}
                for i in insight_rows
            ],
            "synthesized_insights": [
                {"title": s.title, "insight": s.body} for s in synth_rows
            ],
            "recent_daily": [
                {
                    "day": s.day.isoformat(),
                    "sleep": s.sleep_score,
                    "recovery": s.recovery_score,
                    "execution": s.execution_score,
                }
                for s in score_rows[-14:]
            ],
        }

    def _citations(self, context: dict) -> list[Citation]:
        cites: list[Citation] = []
        for key, value in context["averages"].items():
            if value is not None:
                cites.append(Citation(metric=f"avg_{key}_{context['window']['days']}d", value=str(value)))
        if context["current_state"]:
            cites.append(
                Citation(metric="current_state", value=context["current_state"]["state"])
            )
        return cites

    def ask(self, req: ChatRequest) -> ChatResponse:
        if not _is_personal_data_question(req.message, req.history):
            return self._ask_general(req)

        context = self.build_context(req.context_days)

        has_data = context["totals"]["days_with_scores"] > 0 or context["totals"][
            "days_with_mood_checkin"
        ] > 0
        if not has_data:
            return ChatResponse(
                answer=(
                    "I don't have any personal data yet. Check Health Auto Export, "
                    "log a Pulse, or sync LifeOS from Settings, then ask me again."
                ),
                citations=[],
                grounded=True,
            )

        messages = [{"role": "system", "content": SYSTEM_PROMPT}]
        # Keep enough continuity for a follow-up without repeatedly feeding the
        # small 4k-context Jetson model an entire long conversation.
        for m in req.history[-6:]:
            messages.append({"role": m.role, "content": m.content})
        messages.append(
            {
                "role": "user",
                "content": (
                    "DATA CONTEXT (JSON, your only source of truth):\n"
                    + json.dumps(context, separators=(",", ":"))
                    + f"\n\nQUESTION: {req.message}"
                ),
            }
        )

        try:
            answer = self._run_agent(messages)
            grounded = True
        except OpenClawTimeout as exc:
            logger.warning("Ask local-model timeout: %s", exc)
            answer = self._fallback(
                context,
                "The local model timed out before it could finish",
            )
            grounded = True
        except OpenClawUnavailable as exc:
            logger.warning("Ask local-model unavailable: %s", exc)
            answer = self._fallback(context, "The local model could not be reached")
            grounded = True

        return ChatResponse(answer=answer, citations=self._citations(context), grounded=grounded)

    def _ask_general(self, req: ChatRequest) -> ChatResponse:
        """Answer a non-LifeOS question without injecting personal data.

        A clearly new topic intentionally starts clean. Short follow-ups keep a
        few turns of history, which preserves conversational continuity without
        letting an old sleep discussion hijack a new coding request.
        """
        messages = [{"role": "system", "content": GENERAL_SYSTEM_PROMPT}]
        if len(req.message.split()) <= 8 and _SHORT_FOLLOW_UP.search(req.message.strip()):
            messages.extend(
                {"role": m.role, "content": m.content}
                for m in req.history[-4:]
            )
        messages.append({"role": "user", "content": req.message})
        timeout = min(
            getattr(self.client, "timeout", settings.openclaw_timeout_seconds),
            settings.openclaw_chat_deadline_seconds,
        )
        try:
            answer = self.client.chat(messages, timeout=timeout)
        except OpenClawTimeout as exc:
            logger.warning("Ask general local-model timeout: %s", exc)
            answer = "The local model timed out before it could finish that answer. Please try again."
        except OpenClawUnavailable as exc:
            logger.warning("Ask general local-model unavailable: %s", exc)
            answer = "The local model could not be reached. Please try again when the Jetson is available."
        return ChatResponse(answer=answer, citations=[], grounded=False)

    def suggest(self, days: int = 30) -> list[str]:
        """Propose data-grounded starter questions without invoking the LLM.

        This endpoint runs when the Ask screen opens. Keeping it deterministic
        prevents a decorative background request from occupying the Jetson's
        only model exactly when the user submits a real question.
        """
        context = self.build_context(days)
        has_data = (
            context["totals"]["days_with_scores"] > 0
            or context["totals"]["days_with_mood_checkin"] > 0
        )
        if not has_data:
            return DEFAULT_SUGGESTIONS

        questions: list[str] = []
        state = context.get("current_state")
        if state and state.get("state") and state["state"] != "unknown":
            questions.append(f"Why am I in a {state['state']} state?")
        averages = context.get("averages") or {}
        if averages.get("sleep_score") is not None and averages.get("execution_score") is not None:
            questions.append("How is my sleep affecting execution?")
        if averages.get("burnout_risk") is not None:
            questions.append("Is my burnout risk changing?")
        if context.get("spending"):
            questions.append("What is driving my recent spending?")
        if context.get("discovered_patterns"):
            questions.append("Which habits most affect my performance?")
        for question in DEFAULT_SUGGESTIONS:
            if question not in questions:
                questions.append(question)
        return questions[:4]

    def _run_agent(self, messages: list[dict]) -> str:
        """Agentic loop: let OpenClaw call data tools, then answer.

        Each turn we offer the tool schemas. If the model asks to call tools we
        execute them, append the results, and loop (bounded by MAX_AGENT_STEPS).
        When it stops requesting tools — or a server ignores tools entirely — its
        ``content`` is the final answer. Raises ``OpenClawUnavailable`` so the
        caller can fall back to a deterministic summary.
        """
        tools = AgentTools(self.db)
        deadline = monotonic() + settings.openclaw_chat_deadline_seconds

        def remaining_timeout() -> float:
            remaining = deadline - monotonic()
            if remaining <= 1.0:
                raise OpenClawTimeout("Ask request exceeded its overall time budget")
            return min(getattr(self.client, "timeout", settings.openclaw_timeout_seconds), remaining)

        for _ in range(MAX_AGENT_STEPS):
            message = self.client.chat_with_tools(
                messages,
                agent_tools.TOOL_SCHEMAS,
                timeout=remaining_timeout(),
            )
            tool_calls = message.get("tool_calls") or []
            if not tool_calls:
                return message.get("content") or ""

            # Record the assistant's tool-call turn, then answer each call.
            messages.append(message)
            for call in tool_calls:
                fn = (call.get("function") or {})
                name = fn.get("name", "")
                try:
                    args = json.loads(fn.get("arguments") or "{}")
                except (ValueError, TypeError):
                    args = {}
                result = tools.run(name, args)
                messages.append(
                    {
                        "role": "tool",
                        "tool_call_id": call.get("id", ""),
                        "name": name,
                        "content": json.dumps(result),
                    }
                )

        # Hit the step cap — ask once more, without tools, for a closing answer.
        return self.client.chat(messages, timeout=remaining_timeout())

    def _fallback(self, context: dict, reason: str) -> str:
        """Deterministic, data-only answer when local-model generation fails.

        Still grounded — it only restates numbers we actually have.
        """
        avgs = context["averages"]
        parts = [f"{reason}, so here is a data-only summary:"]
        for key, value in avgs.items():
            if value is not None:
                parts.append(f"- {key.replace('_', ' ')}: {value} (avg over {context['window']['days']}d)")
        if context["current_state"]:
            parts.append(
                f"- current state: {context['current_state']['state']} "
                f"({context['current_state']['rationale']})"
            )
        if context["discovered_patterns"]:
            parts.append("Discovered patterns:")
            for p in context["discovered_patterns"][:5]:
                parts.append(f"- {p['summary']}")
        return "\n".join(parts)
