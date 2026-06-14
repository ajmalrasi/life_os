"""Proactive notification generator.

Derives actionable, data-grounded nudges from recent scores/states. Stateless:
the Android client polls this and decides what to surface. Each notification
carries the metric that triggered it so nothing is unexplained.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta

from sqlalchemy.orm import Session

from app.models.daily_state import LifeState
from app.repositories.analytics_repo import ScoreRepository, StateRepository
from app.repositories.github_repo import GithubRepository


@dataclass
class Notification:
    severity: str  # "info" | "warning" | "positive"
    title: str
    detail: str


SLEEP_DEBT_THRESHOLD = 60.0  # sleep score below this counts toward debt


class NotificationService:
    def __init__(self, db: Session) -> None:
        self.scores = ScoreRepository(db)
        self.states = StateRepository(db)
        self.github = GithubRepository(db)

    def generate(self) -> list[Notification]:
        end = date.today()
        start = end - timedelta(days=13)
        scores = self.scores.range(start, end)
        states = self.states.range(start, end)
        gh = self.github.range(start, end)
        out: list[Notification] = []

        out.extend(self._sleep_debt(scores))
        out.extend(self._burnout(scores, states))
        out.extend(self._peak(states))
        out.extend(self._declining_execution(scores))
        out.extend(self._idle_projects(gh))
        return out

    def _sleep_debt(self, scores) -> list[Notification]:
        recent = scores[-7:]
        streak = 0
        for r in reversed(recent):
            if r.sleep_score is not None and r.sleep_score < SLEEP_DEBT_THRESHOLD:
                streak += 1
            elif r.sleep_score is not None:
                break
        if streak >= 3:
            return [
                Notification(
                    "warning",
                    f"Sleep debt has accumulated for {streak} days",
                    f"Sleep score has been below {SLEEP_DEBT_THRESHOLD:.0f} for {streak} "
                    "consecutive days. Recovery and focus typically follow sleep.",
                )
            ]
        return []

    def _burnout(self, scores, states) -> list[Notification]:
        latest = scores[-1] if scores else None
        if latest and latest.burnout_risk is not None and latest.burnout_risk >= 65:
            return [
                Notification(
                    "warning",
                    "Current pattern resembles previous burnout periods",
                    f"Burnout risk is {latest.burnout_risk:.0f}. Consider a recovery day.",
                )
            ]
        return []

    def _peak(self, states) -> list[Notification]:
        latest = states[-1] if states else None
        if latest and latest.state == LifeState.PEAK_PERFORMANCE and latest.confidence >= 0.5:
            return [
                Notification(
                    "positive",
                    "You are entering a high-performance phase",
                    latest.rationale or "High recovery and execution detected.",
                )
            ]
        return []

    def _declining_execution(self, scores) -> list[Notification]:
        recent = [s.execution_score for s in scores[-8:] if s.execution_score is not None]
        if len(recent) >= 6:
            first = sum(recent[: len(recent) // 2]) / (len(recent) // 2)
            second = sum(recent[len(recent) // 2 :]) / (len(recent) - len(recent) // 2)
            if first > 0 and second < first * 0.6:
                return [
                    Notification(
                        "warning",
                        "Project activity is declining",
                        f"Execution dropped from ~{first:.0f} to ~{second:.0f} over the last week.",
                    )
                ]
        return []

    def _idle_projects(self, gh) -> list[Notification]:
        recent = gh[-4:]
        if recent and all(g.commits == 0 and g.coding_minutes == 0 for g in recent) and len(recent) >= 3:
            return [
                Notification(
                    "info",
                    "No project activity recently",
                    f"No commits or coding time logged in the last {len(recent)} days.",
                )
            ]
        return []
