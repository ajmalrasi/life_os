"""Scoring engine.

Pure functions that turn raw daily signals into 0-100 scores. Keeping these
pure (no DB access) makes them trivial to unit-test and to reason about, and
lets us recompute history whenever the formulas evolve.

These formulas are deliberately transparent and bounded. They are heuristics,
not medical measures — the *correlation* engine is what discovers what actually
matters for a given person; the scores just provide stable, comparable features.
"""

from __future__ import annotations

from dataclasses import dataclass


def _clamp(value: float, low: float = 0.0, high: float = 100.0) -> float:
    return max(low, min(high, value))


def _triangular(value: float, ideal: float, span: float) -> float:
    """100 at ``ideal``, decaying linearly to 0 once ``span`` away."""
    if span <= 0:
        return 100.0 if value == ideal else 0.0
    return _clamp(100.0 * (1.0 - abs(value - ideal) / span))


@dataclass
class SleepInputs:
    total_hours: float | None
    deep_hours: float | None = None
    rem_hours: float | None = None
    awake_hours: float | None = None


@dataclass
class RecoveryInputs:
    hrv_ms: float | None = None
    hrv_baseline_ms: float | None = None
    resting_hr: float | None = None
    resting_hr_baseline: float | None = None
    blood_oxygen_pct: float | None = None
    sleep_score: float | None = None


@dataclass
class ExecutionInputs:
    commits: int = 0
    coding_minutes: int = 0
    active_repos: int = 0


@dataclass
class FitnessInputs:
    """Physical-activity signals for a single day, from Health Connect."""

    workout_minutes: float | None = None   # intentional exercise (ExerciseSession)
    active_calories: float | None = None   # kcal burned that day
    steps: float | None = None             # step count (baseline NEAT movement)


@dataclass
class WellnessInputs:
    """Subjective Pulse check-in fields, each on a 1-5 Likert scale."""

    mood: int | None = None    # 1 (low) .. 5 (great)
    energy: int | None = None  # 1 .. 5
    focus: int | None = None   # 1 .. 5
    stress: int | None = None  # 1 (calm) .. 5 (stressed) — inverted below


def _likert_to_100(value: int | None, invert: bool = False) -> float | None:
    """Map a 1-5 Likert value to 0-100. ``invert`` flips it (for stress)."""
    if value is None:
        return None
    v = max(1, min(5, value))
    if invert:
        v = 6 - v
    return (v - 1) / 4.0 * 100.0


def wellness_score(inp: WellnessInputs) -> float | None:
    """Mental-wellbeing score (0-100) from the four Pulse fields.

    Mood, energy, and focus contribute positively; stress is inverted (calm is
    good). Returns ``None`` when no Pulse data exists for the day so missing
    check-ins never read as "zero wellbeing".
    """
    components: list[tuple[float, float]] = []  # (value, weight)
    mood = _likert_to_100(inp.mood)
    energy = _likert_to_100(inp.energy)
    focus = _likert_to_100(inp.focus)
    calm = _likert_to_100(inp.stress, invert=True)

    if mood is not None:
        components.append((mood, 0.40))    # mood weighted highest
    if energy is not None:
        components.append((energy, 0.25))
    if calm is not None:
        components.append((calm, 0.20))
    if focus is not None:
        components.append((focus, 0.15))

    if not components:
        return None

    total_weight = sum(w for _, w in components)
    score = sum(v * w for v, w in components) / total_weight
    return round(_clamp(score), 1)


def fitness_score(inp: FitnessInputs) -> float | None:
    """Physical-activity score (0-100) — a life *driver*, not a vanity metric.

    LifeOS uses this to learn how movement affects execution, mood, and recovery
    (e.g. "you ship more the day after you train"). Intentional training dominates;
    daily steps provide a baseline; calories corroborate effort.

    Returns ``None`` only when no activity data was synced for the day, so a
    missing sync never reads as sedentary. A *synced-but-sedentary* day legitimately
    scores low — that is a real signal the correlation engine can use.
    """
    if inp.workout_minutes is None and inp.steps is None and inp.active_calories is None:
        return None

    workout = inp.workout_minutes or 0.0
    steps = inp.steps or 0.0
    calories = inp.active_calories or 0.0

    # 45 min of intentional training saturates the workout component.
    workout_c = _clamp(workout / 45.0 * 100.0)
    # 10k steps is the classic "active day" target.
    steps_c = _clamp(steps / 10000.0 * 100.0)
    # ~700 kcal of activity energy saturates (rough; personal baselines come later).
    calories_c = _clamp(calories / 700.0 * 100.0)

    score = 0.55 * workout_c + 0.30 * steps_c + 0.15 * calories_c
    return round(_clamp(score), 1)


def sleep_score(inp: SleepInputs) -> float | None:
    """Reward duration near ~8h plus healthy deep/REM proportion."""
    if inp.total_hours is None:
        return None

    # Duration component: ideal 8h, fully decays 3.5h away (i.e. <4.5h or >11.5h => 0).
    duration = _triangular(inp.total_hours, ideal=8.0, span=3.5)

    # Architecture bonus only when stage data is present.
    stage_component: float | None = None
    if inp.deep_hours is not None or inp.rem_hours is not None:
        deep = inp.deep_hours or 0.0
        rem = inp.rem_hours or 0.0
        total = inp.total_hours or 1.0
        deep_ratio = deep / total
        rem_ratio = rem / total
        # Healthy ranges: deep ~13-23%, REM ~20-25%.
        deep_q = _triangular(deep_ratio, ideal=0.18, span=0.18)
        rem_q = _triangular(rem_ratio, ideal=0.22, span=0.22)
        stage_component = 0.5 * deep_q + 0.5 * rem_q

    if stage_component is None:
        score = duration
    else:
        score = 0.7 * duration + 0.3 * stage_component

    # Penalize fragmentation.
    if inp.awake_hours:
        score -= min(15.0, inp.awake_hours * 10.0)

    return round(_clamp(score), 1)


def recovery_score(inp: RecoveryInputs) -> float | None:
    """Blend HRV trend, resting-HR trend, SpO2, and sleep quality."""
    components: list[tuple[float, float]] = []  # (value, weight)

    if inp.hrv_ms is not None:
        if inp.hrv_baseline_ms and inp.hrv_baseline_ms > 0:
            # Above baseline HRV => good recovery. Ratio 1.0 -> 70, 1.3+ -> 100.
            ratio = inp.hrv_ms / inp.hrv_baseline_ms
            hrv_c = _clamp(70.0 + (ratio - 1.0) * 100.0)
        else:
            # No baseline: map absolute HRV 20ms->40, 80ms->95.
            hrv_c = _clamp(40.0 + (inp.hrv_ms - 20.0) * (55.0 / 60.0))
        components.append((hrv_c, 0.4))

    if inp.resting_hr is not None:
        if inp.resting_hr_baseline and inp.resting_hr_baseline > 0:
            # Below baseline RHR => good. +5bpm above baseline -> 50.
            delta = inp.resting_hr - inp.resting_hr_baseline
            rhr_c = _clamp(80.0 - delta * 6.0)
        else:
            rhr_c = _triangular(inp.resting_hr, ideal=55.0, span=40.0)
        components.append((rhr_c, 0.3))

    if inp.blood_oxygen_pct is not None:
        # 95-100% is healthy; below 90% drops fast.
        spo2_c = _clamp((inp.blood_oxygen_pct - 90.0) * 10.0)
        components.append((spo2_c, 0.1))

    if inp.sleep_score is not None:
        components.append((inp.sleep_score, 0.2))

    if not components:
        return None

    total_weight = sum(w for _, w in components)
    score = sum(v * w for v, w in components) / total_weight
    return round(_clamp(score), 1)


def execution_score(inp: ExecutionInputs) -> float | None:
    """Project output for the day. 0 commits & 0 minutes => 0 (a real avoidance signal)."""
    if inp.commits == 0 and inp.coding_minutes == 0 and inp.active_repos == 0:
        return 0.0

    # Diminishing returns via soft caps.
    commit_c = _clamp(inp.commits * 12.0)             # ~8 commits saturates
    time_c = _clamp(inp.coding_minutes / 240.0 * 100) # 4h of focused coding saturates
    repo_c = _clamp(inp.active_repos * 25.0)          # spreading across repos

    score = 0.45 * commit_c + 0.45 * time_c + 0.10 * repo_c
    return round(_clamp(score), 1)


def consistency_score(execution_history: list[float]) -> float | None:
    """How steady recent execution has been. Low variance + non-zero mean => high."""
    values = [v for v in execution_history if v is not None]
    if len(values) < 3:
        return None
    mean = sum(values) / len(values)
    if mean == 0:
        return 0.0
    variance = sum((v - mean) ** 2 for v in values) / len(values)
    std = variance ** 0.5
    cv = std / mean  # coefficient of variation
    # cv 0 -> 100, cv 1+ -> 0.
    return round(_clamp(100.0 * (1.0 - cv)), 1)


def burnout_risk(
    recovery_history: list[float | None],
    execution_history: list[float | None],
    stress_history: list[int | None],
) -> float | None:
    """Sustained high output + poor recovery + high stress => elevated risk.

    Operates over a trailing window (caller supplies recent days).
    """

    def _avg(xs: list) -> float | None:
        vals = [x for x in xs if x is not None]
        return sum(vals) / len(vals) if vals else None

    rec = _avg(recovery_history)
    exe = _avg(execution_history)
    stress = _avg(stress_history)

    if rec is None and exe is None and stress is None:
        return None

    risk = 0.0
    weight = 0.0
    if rec is not None:
        risk += (100.0 - rec) * 0.45  # low recovery drives risk
        weight += 0.45
    if exe is not None:
        risk += exe * 0.30            # high sustained output drives risk
        weight += 0.30
    if stress is not None:
        risk += (stress / 5.0 * 100.0) * 0.25  # 1-5 scale
        weight += 0.25

    if weight == 0:
        return None
    return round(_clamp(risk / weight), 1)
