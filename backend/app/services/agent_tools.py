"""Tools the OpenClaw agent can call.

Phase 1 #6 turns OpenClaw from pure Q&A into a small agent: instead of only
seeing a pre-built context snapshot, it can call these tools to pull exactly the
slice of data a question needs. Each tool is a read over the same repositories
the deterministic engines use, so the agent can never see anything the user
hasn't already collected, and every number it cites traces back to stored data.

The schemas follow the OpenAI function-calling format that OpenClaw's
OpenAI-compatible endpoint understands. ``AgentTools.run`` dispatches a tool
call to the matching method and returns a JSON-serializable result.
"""

from __future__ import annotations

from datetime import date, timedelta

from sqlalchemy.orm import Session

from app.repositories.analytics_repo import ScoreRepository, StateRepository
from app.repositories.github_repo import GithubRepository
from app.repositories.mood_repo import MoodRepository

_MAX_DAYS = 90


# OpenAI-style function schemas advertised to the model.
TOOL_SCHEMAS: list[dict] = [
    {
        "type": "function",
        "function": {
            "name": "git_log",
            "description": (
                "Per-day coding execution (commits, coding minutes, active repos) over the "
                "last N days, from the unified git + GitHub execution table."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "days": {
                        "type": "integer",
                        "description": "How many trailing days to return (1-90).",
                        "default": 14,
                    }
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "github_events",
            "description": (
                "Per-day GitHub code churn (commits, additions, deletions) over the last N "
                "days. Use to reason about how much code was written, not just commit counts."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "days": {
                        "type": "integer",
                        "description": "How many trailing days to return (1-90).",
                        "default": 14,
                    }
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "health_summary",
            "description": (
                "Averaged sleep/recovery/wellness/execution scores plus mood/energy/focus/"
                "stress and the latest classified life state over the last N days."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "days": {
                        "type": "integer",
                        "description": "How many trailing days to summarize (1-90).",
                        "default": 14,
                    }
                },
            },
        },
    },
]

_TOOL_NAMES = {t["function"]["name"] for t in TOOL_SCHEMAS}


class AgentTools:
    def __init__(self, db: Session) -> None:
        self.db = db
        self.scores = ScoreRepository(db)
        self.states = StateRepository(db)
        self.mood = MoodRepository(db)
        self.github = GithubRepository(db)

    def run(self, name: str, arguments: dict | None = None) -> dict:
        """Dispatch a tool call. Never raises — returns an ``error`` payload instead."""
        args = arguments or {}
        if name not in _TOOL_NAMES:
            return {"error": f"unknown tool '{name}'", "available": sorted(_TOOL_NAMES)}
        days = _clamp_days(args.get("days", 14))
        handler = {
            "git_log": self.git_log,
            "github_events": self.github_events,
            "health_summary": self.health_summary,
        }[name]
        try:
            return handler(days)
        except Exception as exc:  # tools must degrade, never crash the chat
            return {"error": f"{name} failed: {exc}"}

    # -- tools ---------------------------------------------------------------------------

    def git_log(self, days: int) -> dict:
        start, end = _window(days)
        rows = self.github.range(start, end)
        return {
            "window": _win(start, end, days),
            "days": [
                {
                    "day": r.day.isoformat(),
                    "commits": r.commits,
                    "coding_minutes": r.coding_minutes,
                    "active_repos": r.active_repos,
                }
                for r in rows
            ],
            "totals": {
                "commits": sum(r.commits for r in rows),
                "coding_minutes": sum(r.coding_minutes for r in rows),
                "active_days": sum(1 for r in rows if r.commits > 0),
            },
        }

    def github_events(self, days: int) -> dict:
        start, end = _window(days)
        rows = self.github.range(start, end)
        return {
            "window": _win(start, end, days),
            "days": [
                {
                    "day": r.day.isoformat(),
                    "commits": r.commits,
                    "additions": r.additions,
                    "deletions": r.deletions,
                }
                for r in rows
            ],
            "totals": {
                "commits": sum(r.commits for r in rows),
                "additions": sum(r.additions for r in rows),
                "deletions": sum(r.deletions for r in rows),
            },
        }

    def health_summary(self, days: int) -> dict:
        start, end = _window(days)
        score_rows = self.scores.range(start, end)
        mood_rows = self.mood.range(start, end)
        state_rows = self.states.range(start, end)
        latest = state_rows[-1] if state_rows else None

        def avg(attr: str, rows) -> float | None:
            vals = [getattr(r, attr) for r in rows if getattr(r, attr) is not None]
            return round(sum(vals) / len(vals), 1) if vals else None

        return {
            "window": _win(start, end, days),
            "averages": {
                "sleep_score": avg("sleep_score", score_rows),
                "recovery_score": avg("recovery_score", score_rows),
                "execution_score": avg("execution_score", score_rows),
                "wellness_score": avg("wellness_score", score_rows),
                "burnout_risk": avg("burnout_risk", score_rows),
                "mood": avg("mood", mood_rows),
                "energy": avg("energy", mood_rows),
                "focus": avg("focus", mood_rows),
                "stress": avg("stress", mood_rows),
            },
            "current_state": (
                {
                    "day": latest.day.isoformat(),
                    "state": latest.state.value,
                    "confidence": latest.confidence,
                    "rationale": latest.rationale,
                }
                if latest
                else None
            ),
            "coverage": {
                "days_with_scores": len(score_rows),
                "days_with_pulse": len(mood_rows),
            },
        }


def _clamp_days(value) -> int:
    try:
        days = int(value)
    except (TypeError, ValueError):
        return 14
    return max(1, min(_MAX_DAYS, days))


def _window(days: int) -> tuple[date, date]:
    end = date.today()
    return end - timedelta(days=days - 1), end


def _win(start: date, end: date, days: int) -> dict:
    return {"start": start.isoformat(), "end": end.isoformat(), "days": days}
