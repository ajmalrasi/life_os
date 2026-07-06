"""Proactive notification generator.

Derives actionable, data-grounded nudges from recent scores/states. Stateless:
the Android client polls this and decides what to surface. Each notification
carries the metric that triggered it so nothing is unexplained.
"""

from __future__ import annotations

import statistics
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, timedelta

from sqlalchemy.orm import Session

from app.models.daily_state import LifeState
from app.repositories.analytics_repo import ScoreRepository, StateRepository
from app.repositories.expense_repo import ExpenseRepository
from app.repositories.github_repo import GithubRepository


@dataclass
class Notification:
    severity: str  # "info" | "warning" | "positive"
    title: str
    detail: str


SLEEP_DEBT_THRESHOLD = 60.0  # sleep score below this counts toward debt

# Payment-source silence ("dead man's switch"). A card/account that was clearly
# active but has since gone quiet longer than its own normal rhythm is the only
# signal that catches a transaction going fully un-parsed — an email whose sender
# or format changed so much that nothing (not even the LLM catch-all) fetched it,
# so there's no row to notice the absence of any other way.
SILENCE_WINDOW_DAYS = 120  # history looked at to learn each source's rhythm
SILENCE_MIN_ACTIVE_DAYS = 5  # distinct spending days needed to call it "active"
SILENCE_GAP_MULTIPLE = 4  # flag once silence exceeds this * the usual gap
SILENCE_FLOOR_DAYS = 7  # never nag about a daily-use card before a week is up


class NotificationService:
    def __init__(self, db: Session) -> None:
        self.scores = ScoreRepository(db)
        self.states = StateRepository(db)
        self.github = GithubRepository(db)
        self.expenses = ExpenseRepository(db)

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
        out.extend(self._low_wellness(scores))
        out.extend(self._sedentary(scores))
        out.extend(self._silent_payment_sources(end))
        return out

    def _silent_payment_sources(self, today: date) -> list[Notification]:
        """Flag any card/account that was regularly active but has now been
        silent for far longer than its own typical gap between transactions.

        This is the safety net for the invisible case: if a bank changes its
        sender address or email format enough that no parser fetches the alert,
        no transaction row is ever written — so the *absence* over time is the
        only thing left to notice. Only reasonably-active sources are watched
        (>= SILENCE_MIN_ACTIVE_DAYS distinct days in the window), which keeps
        rarely-used cards from producing noise; a genuinely retired card ages
        out of the window on its own once its last use is > SILENCE_WINDOW_DAYS old.
        """
        start = today - timedelta(days=SILENCE_WINDOW_DAYS)
        by_account: dict[str, set[date]] = defaultdict(set)
        for t in self.expenses.range(start, today):
            if t.account:
                by_account[t.account].add(t.txn_date)

        out: list[Notification] = []
        for account, day_set in by_account.items():
            days = sorted(day_set)
            if len(days) < SILENCE_MIN_ACTIVE_DAYS:
                continue
            gaps = [(b - a).days for a, b in zip(days, days[1:])]
            typical_gap = statistics.median(gaps)
            days_silent = (today - days[-1]).days
            threshold = max(SILENCE_FLOOR_DAYS, round(SILENCE_GAP_MULTIPLE * typical_gap))
            if days_silent > threshold:
                out.append(
                    Notification(
                        "warning",
                        f"No activity from {account} in {days_silent} days",
                        f"{account} usually has a transaction about every "
                        f"{typical_gap:.0f} day(s), but none has come through in "
                        f"{days_silent}. If you've actually used it, its alert email's "
                        "sender or format may have changed and the parser is now "
                        "missing it — worth checking.",
                    )
                )
        # Most-overdue first, then stable by name.
        out.sort(key=lambda n: n.title)
        return out

    def _sedentary(self, scores) -> list[Notification]:
        """Sustained low movement — nudge action, since fitness tends to lift mood
        and execution. Only fires when we actually have fitness data to judge."""
        recent = [s.fitness_score for s in scores[-5:] if s.fitness_score is not None]
        if len(recent) >= 3:
            avg = sum(recent) / len(recent)
            if avg < 30.0:
                return [
                    Notification(
                        "info",
                        "Movement has been low this week",
                        f"Fitness score has averaged {avg:.0f} over the last {len(recent)} days. "
                        "A short workout or walk often lifts mood and focus the same day.",
                    )
                ]
        return []

    def _low_wellness(self, scores) -> list[Notification]:
        recent = [s.wellness_score for s in scores[-5:] if s.wellness_score is not None]
        if len(recent) >= 3:
            avg = sum(recent) / len(recent)
            if avg < 45.0:
                return [
                    Notification(
                        "warning",
                        "Your wellbeing has been running low",
                        f"Wellness score has averaged {avg:.0f} over the last "
                        f"{len(recent)} check-ins. Mood, energy, and stress are worth attention.",
                    )
                ]
        return []

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
