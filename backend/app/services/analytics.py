"""Analytics orchestration.

Stitches the repositories and the pure engines (scoring, state, correlation)
into the two operations the system needs:

* ``recompute_day``  — derive scores + state for a single day from raw data.
* ``run_nightly``    — recompute a window of days, then discover correlations.

This is the only service that touches multiple repositories, keeping the
pure engines free of DB concerns.
"""

from __future__ import annotations

from datetime import date, timedelta

from sqlalchemy.orm import Session

from app.config import get_settings
from app.models.health import HealthRecordType
from app.repositories.analytics_repo import (
    InsightRepository,
    ScoreRepository,
    StateRepository,
)
from app.repositories.github_repo import GithubRepository
from app.repositories.health_repo import HealthRepository
from app.repositories.mood_repo import MoodRepository
from app.services import correlation as corr
from app.services import scoring
from app.services import state_engine
from app.services import insight_narrator
from app.services.insight_narrator import InsightFact

settings = get_settings()

_BASELINE_WINDOW = 30  # days used to compute HRV / resting-HR personal baselines
_BURNOUT_WINDOW = 7
_CONSISTENCY_WINDOW = 7


class AnalyticsService:
    def __init__(self, db: Session, client: "OpenClawClient | None" = None) -> None:
        self.db = db
        self.health = HealthRepository(db)
        self.mood = MoodRepository(db)
        self.github = GithubRepository(db)
        self.scores = ScoreRepository(db)
        self.states = StateRepository(db)
        self.insights = InsightRepository(db)
        # Lazily constructed inside the narrator if left as None; injectable for tests.
        self.client = client

    # -- per-day -------------------------------------------------------------------------

    def _sum(self, day: date, t: HealthRecordType) -> float | None:
        vals = self.health.daily_values(day, t)
        return sum(vals) if vals else None

    def _mean(self, day: date, t: HealthRecordType) -> float | None:
        vals = self.health.daily_values(day, t)
        return sum(vals) / len(vals) if vals else None

    def _baseline(self, day: date, t: HealthRecordType, agg: str = "mean") -> float | None:
        # Mean of per-day means over the trailing baseline window, in one query.
        start = day - timedelta(days=_BASELINE_WINDOW)
        return self.health.baseline_mean(start, day, t)

    def recompute_day(self, day: date) -> None:
        # --- sleep ---
        sleep_inputs = scoring.SleepInputs(
            total_hours=self._sum(day, HealthRecordType.SLEEP_DURATION),
            deep_hours=self._sum(day, HealthRecordType.SLEEP_DEEP),
            rem_hours=self._sum(day, HealthRecordType.SLEEP_REM),
            awake_hours=self._sum(day, HealthRecordType.SLEEP_AWAKE),
        )
        sleep = scoring.sleep_score(sleep_inputs)

        # --- recovery ---
        recovery = scoring.recovery_score(
            scoring.RecoveryInputs(
                hrv_ms=self._mean(day, HealthRecordType.HRV),
                hrv_baseline_ms=self._baseline(day, HealthRecordType.HRV),
                resting_hr=self._mean(day, HealthRecordType.RESTING_HEART_RATE),
                resting_hr_baseline=self._baseline(day, HealthRecordType.RESTING_HEART_RATE),
                blood_oxygen_pct=self._mean(day, HealthRecordType.BLOOD_OXYGEN),
                sleep_score=sleep,
            )
        )

        # --- execution ---
        gh = self.github.get(day)
        execution = scoring.execution_score(
            scoring.ExecutionInputs(
                commits=gh.commits if gh else 0,
                coding_minutes=gh.coding_minutes if gh else 0,
                active_repos=gh.active_repos if gh else 0,
                pr_issue_activity=(
                    gh.prs_opened + gh.prs_merged + gh.issues_opened + gh.issues_closed if gh else 0
                ),
            )
        )

        # --- wellness (subjective Pulse check-in) ---
        mood = self.mood.get(day)
        wellness = scoring.wellness_score(
            scoring.WellnessInputs(
                mood=mood.mood if mood else None,
                energy=mood.energy if mood else None,
                focus=mood.focus if mood else None,
                stress=mood.stress if mood else None,
            )
        )

        # --- fitness (physical activity from Health Connect) ---
        fitness = scoring.fitness_score(
            scoring.FitnessInputs(
                workout_minutes=self._sum(day, HealthRecordType.WORKOUT),
                active_calories=self._sum(day, HealthRecordType.CALORIES),
                steps=self._sum(day, HealthRecordType.STEPS),
            )
        )

        # --- consistency (trailing execution) ---
        exec_hist = self._trailing_execution(day, _CONSISTENCY_WINDOW)
        consistency = scoring.consistency_score(exec_hist)

        # --- burnout (trailing recovery/execution/stress) ---
        burnout = self._burnout(day)

        self.scores.upsert(
            day,
            sleep_score=sleep,
            recovery_score=recovery,
            execution_score=execution,
            wellness_score=wellness,
            fitness_score=fitness,
            consistency_score=consistency,
            burnout_risk=burnout,
        )

        # --- state ---
        result = state_engine.classify(
            state_engine.StateInputs(
                sleep_score=sleep,
                recovery_score=recovery,
                execution_score=execution,
                consistency_score=consistency,
                burnout_risk=burnout,
                fitness_score=fitness,
                mood=mood.mood if mood else None,
                focus=mood.focus if mood else None,
                stress=mood.stress if mood else None,
            )
        )
        self.states.upsert(day, result.state, result.confidence, result.rationale)

    def _trailing_execution(self, day: date, window: int) -> list[float]:
        out: list[float] = []
        for i in range(1, window + 1):
            row = self.scores.get(day - timedelta(days=i))
            if row and row.execution_score is not None:
                out.append(row.execution_score)
        return out

    def _burnout(self, day: date) -> float | None:
        rec: list[float | None] = []
        exe: list[float | None] = []
        stress: list[int | None] = []
        for i in range(0, _BURNOUT_WINDOW):
            d = day - timedelta(days=i)
            row = self.scores.get(d)
            rec.append(row.recovery_score if row else None)
            exe.append(row.execution_score if row else None)
            m = self.mood.get(d)
            stress.append(m.stress if m else None)
        return scoring.burnout_risk(rec, exe, stress)

    # -- nightly -------------------------------------------------------------------------

    def run_nightly(self, start: date, end: date) -> tuple[int, int, int]:
        """Recompute [start, end] then rediscover correlations. Returns counts."""
        days = _date_range(start, end)
        for day in days:
            self.recompute_day(day)
        insights = self.discover(start, end)
        return len(days), len(days), insights

    def discover(self, start: date, end: date) -> int:
        """Discover lagged correlations between drivers and outcomes; upsert insights."""
        days = _date_range(start, end)

        score_rows = {r.day: r for r in self.scores.range(start, end)}
        mood_rows = {m.day: m for m in self.mood.range(start, end)}

        def series(getter) -> list[float | None]:
            return [getter(day) for day in days]

        sleep = series(lambda d: getattr(score_rows.get(d), "sleep_score", None))
        recovery = series(lambda d: getattr(score_rows.get(d), "recovery_score", None))
        execution = series(lambda d: getattr(score_rows.get(d), "execution_score", None))
        wellness = series(lambda d: getattr(score_rows.get(d), "wellness_score", None))
        fitness = series(lambda d: getattr(score_rows.get(d), "fitness_score", None))
        mood_s = series(lambda d: getattr(mood_rows.get(d), "mood", None))
        focus_s = series(lambda d: getattr(mood_rows.get(d), "focus", None))

        named = {
            "sleep_score": sleep,
            "recovery_score": recovery,
            "execution_score": execution,
            "wellness_score": wellness,
            "fitness_score": fitness,
            "mood": mood_s,
            "focus": focus_s,
        }

        # Driver -> candidate outcomes. We intentionally test plausible causal links.
        pairs = [
            ("sleep_score", "execution_score"),
            ("sleep_score", "focus"),
            ("sleep_score", "mood"),
            ("sleep_score", "wellness_score"),
            ("recovery_score", "execution_score"),
            ("recovery_score", "mood"),
            ("recovery_score", "wellness_score"),
            ("execution_score", "mood"),
            ("wellness_score", "execution_score"),
            # Fitness as a driver of productivity, mood, and sleep — the "move to
            # perform" hypothesis this app exists to test.
            ("fitness_score", "execution_score"),
            ("fitness_score", "mood"),
            ("fitness_score", "wellness_score"),
            ("fitness_score", "focus"),
            ("fitness_score", "sleep_score"),
        ]

        # First pass: the deterministic correlation engine decides which patterns
        # are real and computes their grounding facts.
        facts: list[InsightFact] = []
        for driver_name, outcome_name in pairs:
            driver = named[driver_name]
            outcome = named[outcome_name]
            c = corr.lagged_correlation(
                driver,
                outcome,
                driver_name,
                outcome_name,
                max_lag=3,
                min_samples=settings.correlation_min_samples,
            )
            if c is None or abs(c.r) < settings.correlation_significance:
                continue
            summary = corr.describe(c, driver, outcome)
            facts.append(
                InsightFact(
                    driver=c.driver,
                    outcome=c.outcome,
                    r=c.r,
                    n=c.n,
                    lag_days=c.lag_days,
                    summary=summary,
                )
            )

        # Second pass (best-effort): the LLM retells those same facts in fresher
        # language. Failures here never block discovery — we still persist the
        # deterministic summaries.
        narratives: dict[str, str] = {}
        try:
            narratives = insight_narrator.narrate(facts, client=self.client)
        except Exception:  # narration is a nicety, never a blocker
            narratives = {}

        for f in facts:
            self.insights.upsert(
                driver=f.driver,
                outcome=f.outcome,
                correlation=f.r,
                sample_size=f.n,
                lag_days=f.lag_days,
                summary=f.summary,
                narrative=narratives.get(f.key()),
            )
        return len(facts)


def _date_range(start: date, end: date) -> list[date]:
    days: list[date] = []
    d = start
    while d <= end:
        days.append(d)
        d += timedelta(days=1)
    return days
