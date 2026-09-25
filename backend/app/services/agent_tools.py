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

from datetime import date, datetime, timedelta
from time import monotonic
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.repositories.analytics_repo import ScoreRepository, StateRepository
from app.repositories.github_repo import GithubRepository
from app.repositories.mood_repo import MoodRepository
from app.services.github_mcp_client import GitHubMcpClient
from app.services.money_query import query_money
from app.models.health import HealthRecordType
from app.repositories.health_repo import HealthRepository
from app.models.trip import TripBackup

_MAX_DAYS = 90

# OpenAI-style function schemas advertised to the model.
TOOL_SCHEMAS: list[dict] = [
    {"type": "function", "function": {
        "name": "get_trip_context",
        "description": "Read a backed-up trip itinerary, completed activities, visits, multi-step transit routes (metro stations, lines, platforms, train coaches, seats, road pickup zones), and expenses. Use for questions about travel plans, routes, transfers, or what happened during a trip.",
        "parameters": {"type": "object", "properties": {
            "trip_id": {"type": "string", "description": "LifeOS trip id. Omit to read the most recently updated trip."}
        }},
    }},
    {"type": "function", "function": {
        "name": "get_life_context",
        "description": "Read a broad overview of scores, Pulse, life state, spending, and discovered insights.",
        "parameters": {"type": "object", "properties": {
            "days": {"type": "integer", "description": "Trailing days, 1-365.", "default": 30}
        }},
    }},
    {
        "type": "function",
        "function": {
            "name": "query_money",
            "description": (
                "Run a guarded read-only analytical query over the stored transactions "
                "table. Use for exact spending/income questions that need arbitrary date "
                "ranges, category/merchant/account/source filters, rankings, transaction "
                "details, or day/week/month/category/merchant/account breakdowns. "
                "For comparisons use compare_start_date/compare_end_date in the SAME call; "
                "the tool computes differences and percentages. Statement detail rows "
                "are not transactions and never count as spend. "
                "For 'last month', send explicit start_date/end_date ISO dates from the "
                "system prompt current date. Effective categories honor Money overrides; "
                "Ask includes EMI transactions even when the Money page hides them."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "mode": {
                        "type": "string",
                        "enum": ["spending", "income", "all"],
                        "description": (
                            "spending = real debits only (transfers/returns excluded); "
                            "income = real credits; all = raw debit and credit rows."
                        ),
                        "default": "spending",
                    },
                    "start_date": {
                        "type": "string",
                        "description": "Inclusive ISO date YYYY-MM-DD. Defaults to 30 days.",
                    },
                    "end_date": {
                        "type": "string",
                        "description": "Inclusive ISO date YYYY-MM-DD. Defaults to today.",
                    },
                    "compare_start_date": {
                        "type": "string", "description": "Baseline window start, ISO date. Requires compare_end_date.",
                    },
                    "compare_end_date": {
                        "type": "string", "description": "Baseline window end, ISO date. Compare primary start/end against this period.",
                    },
                    "group_by": {
                        "type": "string",
                        "enum": [
                            "none", "day", "week", "month", "category",
                            "counterparty", "account", "source", "direction",
                        ],
                        "description": (
                            "Breakdown dimension. Use none for matching transaction rows."
                        ),
                        "default": "none",
                    },
                    "category": {
                        "type": "string",
                        "description": "Exact effective Money-page category, e.g. food.",
                    },
                    "counterparty_contains": {
                        "type": "string",
                        "description": "Case-insensitive merchant/payee text filter.",
                    },
                    "account_contains": {
                        "type": "string",
                        "description": "Case-insensitive bank/card/account text filter.",
                    },
                    "source": {
                        "type": "string",
                        "description": "Exact stored source such as upi or card_live.",
                    },
                    "min_amount": {"type": "number"},
                    "max_amount": {"type": "number"},
                    "sort_by": {
                        "type": "string",
                        "enum": ["total", "count", "average", "max", "date", "amount", "name"],
                    },
                    "sort_order": {
                        "type": "string",
                        "enum": ["asc", "desc"],
                        "default": "desc",
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Maximum result rows (1-50).",
                        "default": 10,
                    },
                },
            },
        },
    },
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
            "name": "github_search",
            "description": (
                "Query GitHub live via the official github-mcp-server for recent commits, "
                "file contents, or code search. Use when you need information beyond what "
                "is cached in the local database — e.g. a specific commit message, diff, "
                "or activity on a repo not yet synced."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "tool": {
                        "type": "string",
                        "description": (
                            "The github-mcp-server tool to call. Common values: "
                            "'list_commits', 'get_commit', 'search_commits', "
                            "'get_file_contents', 'list_branches'."
                        ),
                    },
                    "arguments": {
                        "type": "object",
                        "description": (
                            "Arguments for the chosen tool. For list_commits: "
                            "{'owner':'user','repo':'name','since':'ISO8601','until':'ISO8601'}. "
                            "For get_commit: {'owner':'user','repo':'name','sha':'<sha>'}."
                        ),
                    },
                },
                "required": ["tool", "arguments"],
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
    {
        "type": "function",
        "function": {
            "name": "health_records",
            "description": (
                "Stored Apple Health data synced from the user's iPhone and Apple Watch: "
                "per-day sleep hours and stages, resting heart rate, HRV, steps, active "
                "energy and exercise minutes, plus every workout with its type (e.g. "
                "Traditional Strength Training) and the last training workout. Walks logged "
                "as workouts are marked as walking, not training. Counts and minutes are "
                "computed for the exact window; report them instead of counting yourself. "
                "Pass start_date and end_date for a calendar period such as a month."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "days": {
                        "type": "integer",
                        "description": "Trailing days to cover (1-90) when no dates are given.",
                        "default": 14,
                    },
                    "start_date": {"type": "string", "description": "ISO date, e.g. 2026-07-01."},
                    "end_date": {"type": "string", "description": "ISO date, inclusive, e.g. 2026-07-31."},
                },
            },
        },
    },
]

_TOOL_NAMES = {t["function"]["name"] for t in TOOL_SCHEMAS}


class AgentTools:
    def __init__(self, db: Session, deadline: float | None = None) -> None:
        self.db = db
        self.deadline = deadline
        self.scores = ScoreRepository(db)
        self.states = StateRepository(db)
        self.mood = MoodRepository(db)
        self.github = GithubRepository(db)
        settings = get_settings()
        self._mcp = GitHubMcpClient(token=settings.github_token)

    def run(self, name: str, arguments: dict | None = None) -> dict:
        if not isinstance(arguments, (dict, type(None))):
            return {"status": "error", "error": "Arguments must be an object"}
        schema = next((t["function"]["parameters"] for t in TOOL_SCHEMAS
                       if t["function"]["name"] == name), None)
        if schema:
            args = arguments or {}
            properties = schema["properties"]
            if set(args) - set(properties):
                return {"status": "error", "error": "Unknown tool argument"}
            for key, value in args.items():
                kind = properties[key].get("type")
                valid = {"string": isinstance(value, str),
                         "integer": type(value) is int,
                         "number": type(value) in (int, float),
                         "object": isinstance(value, dict)}.get(kind, True)
                if not valid:
                    return {"status": "error", "error": f"Invalid type for {key}"}
        result = self._run(name, arguments)
        if "error" in result:
            return {**result, "status": "error"}
        return {**result, "retrieved_at": datetime.now(ZoneInfo("Asia/Kolkata")).isoformat(),
                "timezone": "Asia/Kolkata", "source": result.get("source", name),
                "units": {"money": "INR", "scores": "0-100", "coding_minutes": "minutes",
                          "pulse": "recorded check-in scale"},
                "coverage_note": "Only recorded data. Missing readings are unknown; dates describe coverage, not sync freshness."}

    def _run(self, name: str, arguments: dict | None = None) -> dict:
        """Dispatch a tool call. Never raises — returns an ``error`` payload instead."""
        args = arguments or {}
        if name not in _TOOL_NAMES:
            return {"error": f"unknown tool '{name}'", "available": sorted(_TOOL_NAMES)}
        try:
            if name == "get_life_context":
                from app.services.chat_service import ChatService
                days = max(1, min(365, int(args.get("days", 30))))
                return ChatService(self.db).build_context(days)
            if name == "get_trip_context":
                return self.get_trip_context(args.get("trip_id"))
            if name == "github_search":
                return self.github_search(
                    tool=args.get("tool", ""),
                    arguments=args.get("arguments"),
                )
            if name == "health_records":
                return self.health_records(
                    _clamp_days(args.get("days", 14)), args.get("start_date"), args.get("end_date"),
                )
            if name == "query_money":
                return query_money(self.db, **args)
            days = _clamp_days(args.get("days", 14))
            handler = {
                "git_log": self.git_log,
                "github_events": self.github_events,
                "health_summary": self.health_summary,
            }[name]
            return handler(days)
        except Exception as exc:  # tools must degrade, never crash the chat
            return {"error": f"{name} failed: {exc}"}

    # -- tools ---------------------------------------------------------------------------

    def get_trip_context(self, trip_id: str | None = None) -> dict:
        query = select(TripBackup).where(TripBackup.deleted_at.is_(None))
        if trip_id:
            query = query.where(TripBackup.id == trip_id)
        else:
            query = query.order_by(TripBackup.updated_at.desc()).limit(1)
        row = self.db.scalar(query)
        if row is None:
            return {"error": "No matching trip backup is available."}
        payload = dict(row.payload)
        locations = payload.pop("locations", [])
        return {
            "trip_id": row.id,
            "revision": row.revision,
            "updated_at": row.updated_at.isoformat(),
            "trip": payload,
            "recorded_location_count": len(locations),
            "freshness_note": "This is the last phone backup; unsynced phone changes are not included.",
        }

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

    def github_search(self, tool: str = "", arguments: dict | None = None) -> dict:
        """Proxy a tool call to the github-mcp-server and return its result."""
        if not tool:
            return {"error": "github_search requires a 'tool' argument"}
        if tool not in {"list_commits", "get_commit", "search_commits", "get_file_contents",
                        "list_branches", "search_code"}:
            return {"error": "Unsupported read-only GitHub operation"}
        result = self._mcp.call_tool(tool, arguments or {}, timeout=self._remaining(30))
        if result is None:
            return {"error": f"github-mcp-server unreachable or tool '{tool}' failed"}
        return {"tool": tool, "result": result}

    def health_records(self, days: int = 14, start_date: str | None = None, end_date: str | None = None) -> dict:
        """Synced Apple Health readings and workouts; never contacts the phone."""
        from app.services.day_brief import is_walk_workout

        start, end = _window(days)
        if start_date or end_date:
            try:
                start = date.fromisoformat(start_date) if start_date else start
                end = date.fromisoformat(end_date) if end_date else end
            except ValueError:
                return {"error": "start_date and end_date must be ISO dates like 2026-07-01"}
            if end < start:
                return {"error": "end_date is before start_date"}
            end = min(end, datetime.now(ZoneInfo("Asia/Kolkata")).date())
            start = max(start, end - timedelta(days=_MAX_DAYS - 1))
            days = (end - start).days + 1
        health = HealthRepository(self.db)

        def total(d: date, t) -> float | None:
            vals = health.daily_values(d, t)
            return round(sum(vals), 1) if vals else None

        def mean(d: date, t) -> float | None:
            vals = health.daily_values(d, t)
            return round(sum(vals) / len(vals), 1) if vals else None

        # Newest days first: the chat bounds long lists, and recent days matter most.
        daily = []
        d = end
        while d >= start:
            night = health.sleep_for_day(d)
            daily.append({
                "day": d.isoformat(),
                "sleep_hours": round(night.total_hours, 2) if night else None,
                "deep_hours": round(night.deep_hours, 2) if night and night.deep_hours is not None else None,
                "rem_hours": round(night.rem_hours, 2) if night and night.rem_hours is not None else None,
                "resting_hr_bpm": mean(d, HealthRecordType.RESTING_HEART_RATE),
                "hrv_ms": mean(d, HealthRecordType.HRV),
                "steps": total(d, HealthRecordType.STEPS),
                "active_kcal": total(d, HealthRecordType.CALORIES),
                "exercise_minutes": total(d, "apple_exercise_time"),
            })
            d -= timedelta(days=1)

        def workout(w) -> dict:
            local = w.start_time.astimezone(ZoneInfo("Asia/Kolkata")) if w.start_time.tzinfo else w.start_time
            return {
                "day": local.date().isoformat(),
                "start": local.strftime("%H:%M"),
                "type": w.label or "Workout (type not recorded)",
                "minutes": round(w.value, 1),
                "kind": "walking" if is_walk_workout(w.label) else "training",
            }

        in_window = [workout(w) for w in reversed(health.workouts_between(start, end))]
        training = [w for w in health.workouts_between(date(1970, 1, 1), end) if not is_walk_workout(w.label)]
        last_received = health.last_received_at()
        export_seen = health.workout_export_seen_at()
        return {
            "window": _win(start, end, days),
            "freshness": {
                "health_data_last_arrived": last_received.isoformat() if last_received else None,
                "workouts_sync": (
                    f"active since {export_seen.date().isoformat()}" if export_seen
                    else "not set up; workouts after mid-June 2026 are unknown, not zero"
                ),
                "note": "Data arrives when the iPhone syncs; today and last night may be incomplete.",
            },
            "workouts": in_window,
            "by_month": _workouts_by_month(in_window),
            "window_totals": {
                "training_workouts": sum(1 for w in in_window if w["kind"] == "training"),
                "training_minutes": round(sum(w["minutes"] for w in in_window if w["kind"] == "training"), 1),
                "walking_workouts": sum(1 for w in in_window if w["kind"] == "walking"),
                "training_days": len({w["day"] for w in in_window if w["kind"] == "training"}),
            },
            "last_training_workout": workout(training[-1]) if training else None,
            "daily": daily,
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
                "fitness_score": avg("fitness_score", score_rows),
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
                "latest_score_day": score_rows[-1].day.isoformat() if score_rows else None,
                "latest_pulse_day": mood_rows[-1].day.isoformat() if mood_rows else None,
            },
        }

    def _remaining(self, maximum: float) -> float:
        return maximum if self.deadline is None else max(0.01, min(maximum, self.deadline-monotonic()))


def _workouts_by_month(workouts: list[dict]) -> dict:
    """Per-calendar-month totals, so "how many in July" never needs hand counting."""
    months: dict[str, dict] = {}
    for w in sorted(workouts, key=lambda w: w["day"]):
        m = months.setdefault(w["day"][:7], {
            "training_workouts": 0, "training_minutes": 0.0, "walking_workouts": 0, "_days": set(),
        })
        if w["kind"] == "training":
            m["training_workouts"] += 1
            m["training_minutes"] = round(m["training_minutes"] + w["minutes"], 1)
            m["_days"].add(w["day"])
        else:
            m["walking_workouts"] += 1
    return {k: {**{x: y for x, y in v.items() if x != "_days"}, "training_days": len(v["_days"])}
            for k, v in months.items()}


def _clamp_days(value) -> int:
    try:
        days = int(value)
    except (TypeError, ValueError):
        return 14
    return max(1, min(_MAX_DAYS, days))


def _window(days: int) -> tuple[date, date]:
    end = datetime.now(ZoneInfo("Asia/Kolkata")).date()
    return end - timedelta(days=days - 1), end


def _win(start: date, end: date, days: int) -> dict:
    return {"start": start.isoformat(), "end": end.isoformat(), "days": days}
