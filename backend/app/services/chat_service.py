"""Grounded chat service.

Builds a compact, factual snapshot of the user's recent data and forces the
LLM to answer *only* from it, citing metrics. The system prompt and the
machine-readable context together implement the "no hallucinations, every
conclusion cites supporting metrics" requirement.
"""

from __future__ import annotations

import json
from datetime import date, timedelta

from sqlalchemy.orm import Session

from app.repositories.analytics_repo import (
    InsightRepository,
    ScoreRepository,
    StateRepository,
)
from app.repositories.github_repo import GithubRepository
from app.repositories.mood_repo import MoodRepository
from app.schemas.chat import ChatRequest, ChatResponse, Citation
from app.services import agent_tools
from app.services.agent_tools import AgentTools
from app.services.openclaw import OpenClawClient, OpenClawUnavailable

# Max tool-call round trips before we force a final answer. Keeps the agent loop
# bounded so a misbehaving model can't spin forever.
MAX_AGENT_STEPS = 4

SYSTEM_PROMPT = (
    "You are LifeOS, a personal intelligence companion. You analyze one user's own "
    "health, mood, and project-execution data to explain — in plain language — why "
    "they perform well, procrastinate, or drift toward burnout, and what to do next.\n\n"
    "HOW YOU WORK\n"
    "- Each turn begins with a DATA CONTEXT snapshot (JSON). It is your primary source "
    "of truth.\n"
    "- When you need more than it contains — older days, raw metrics, or commit "
    "detail — call the tools (git_log, github_events, health_summary) before "
    "answering. Don't guess when a tool can tell you.\n\n"
    "GROUNDING RULES (non-negotiable)\n"
    "1. Use ONLY the DATA CONTEXT and tool results. Never invent, estimate, or "
    "round-trip numbers that aren't there.\n"
    "2. Cite a specific metric for every quantitative claim, e.g. '(avg sleep score "
    "78 over 30d)' or '(recovery 52 on 2026-06-14)'.\n"
    "3. A metric that is null, missing, or has zero days of data is UNKNOWN — not "
    "zero and not bad. Say it's unavailable, and if it matters to the question, tell "
    "the user how to fill the gap: sleep and recovery need a wearable syncing to "
    "Health Connect; wellness needs a Pulse check-in; execution needs GitHub "
    "activity.\n"
    "4. Read correlations honestly: |r| >= 0.5 is strong, 0.3-0.5 moderate, < 0.3 "
    "weak. Flag a small sample size (n) instead of overclaiming, and never present "
    "correlation as proven cause.\n\n"
    "STYLE\n"
    "- Concise and direct. Lead with the answer, then the supporting evidence.\n"
    "- Prefer specific, actionable next steps over generic wellness advice.\n"
    "- You are not a clinician: for medical concerns, suggest seeing a professional "
    "rather than interpreting symptoms.\n"
    "- If the data is too sparse to answer, say so plainly and name exactly what to "
    "sync or log to get a real answer."
)


class ChatService:
    def __init__(self, db: Session, client: OpenClawClient | None = None) -> None:
        self.db = db
        self.client = client or OpenClawClient()
        self.scores = ScoreRepository(db)
        self.states = StateRepository(db)
        self.mood = MoodRepository(db)
        self.github = GithubRepository(db)
        self.insights = InsightRepository(db)

    def build_context(self, days: int) -> dict:
        end = date.today()
        start = end - timedelta(days=days - 1)

        score_rows = self.scores.range(start, end)
        state_rows = self.states.range(start, end)
        mood_rows = self.mood.range(start, end)
        gh_rows = self.github.range(start, end)
        insight_rows = self.insights.all()

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
            "discovered_patterns": [
                {"summary": i.summary, "r": i.correlation, "n": i.sample_size}
                for i in insight_rows
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
        context = self.build_context(req.context_days)

        has_data = context["totals"]["days_with_scores"] > 0 or context["totals"][
            "days_with_mood_checkin"
        ] > 0
        if not has_data:
            return ChatResponse(
                answer=(
                    "I don't have any data yet. Sync Health Connect, log a mood "
                    "check-in, or push GitHub activity, then ask me again."
                ),
                citations=[],
                grounded=True,
            )

        messages = [{"role": "system", "content": SYSTEM_PROMPT}]
        for m in req.history[-10:]:
            messages.append({"role": m.role, "content": m.content})
        messages.append(
            {
                "role": "user",
                "content": (
                    "DATA CONTEXT (JSON, your only source of truth):\n"
                    + json.dumps(context, indent=2)
                    + f"\n\nQUESTION: {req.message}"
                ),
            }
        )

        try:
            answer = self._run_agent(messages)
            grounded = True
        except OpenClawUnavailable:
            answer = self._fallback(context, req.message)
            grounded = True

        return ChatResponse(answer=answer, citations=self._citations(context), grounded=grounded)

    def _run_agent(self, messages: list[dict]) -> str:
        """Agentic loop: let OpenClaw call data tools, then answer.

        Each turn we offer the tool schemas. If the model asks to call tools we
        execute them, append the results, and loop (bounded by MAX_AGENT_STEPS).
        When it stops requesting tools — or a server ignores tools entirely — its
        ``content`` is the final answer. Raises ``OpenClawUnavailable`` so the
        caller can fall back to a deterministic summary.
        """
        tools = AgentTools(self.db)
        for _ in range(MAX_AGENT_STEPS):
            message = self.client.chat_with_tools(messages, agent_tools.TOOL_SCHEMAS)
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
        return self.client.chat(messages)

    def _fallback(self, context: dict, question: str) -> str:
        """Deterministic, data-only answer when the LLM server is unreachable.

        Still grounded — it only restates numbers we actually have.
        """
        avgs = context["averages"]
        parts = ["OpenClaw is unreachable, so here is a data-only summary:"]
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
