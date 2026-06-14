"""Daily state classifier.

Rule-based for the MVP: transparent, debuggable, and good enough until there's
enough labeled history to justify a learned model. Returns a state plus a
confidence (how strongly the day's signals point at that state).
"""

from __future__ import annotations

from dataclasses import dataclass

from app.models.daily_state import LifeState


@dataclass
class StateInputs:
    sleep_score: float | None = None
    recovery_score: float | None = None
    execution_score: float | None = None
    consistency_score: float | None = None
    burnout_risk: float | None = None
    fitness_score: float | None = None
    mood: int | None = None      # 1-5
    focus: int | None = None     # 1-5
    stress: int | None = None    # 1-5


@dataclass
class StateResult:
    state: LifeState
    confidence: float
    rationale: str


def classify(inp: StateInputs) -> StateResult:
    rec = inp.recovery_score
    exe = inp.execution_score
    burn = inp.burnout_risk
    focus = inp.focus

    # Score each candidate state 0..1, then pick the strongest.
    scores: dict[LifeState, tuple[float, str]] = {}

    if rec is not None and exe is not None:
        scores[LifeState.PEAK_PERFORMANCE] = (
            _and(_high(rec), _high(exe)),
            f"High recovery ({rec:.0f}) paired with high execution ({exe:.0f}).",
        )

    if exe is not None:
        scores[LifeState.EXECUTION_MODE] = (
            _and(_high(exe), _mid_or_low(rec)),
            f"Strong execution ({exe:.0f}) without standout recovery.",
        )
        scores[LifeState.AVOIDANCE_MODE] = (
            _and(_low(exe), _ok_capacity(rec, inp.sleep_score, inp.fitness_score)),
            f"Low execution ({exe:.0f}) despite adequate capacity — likely avoidance.",
        )

    if burn is not None:
        scores[LifeState.BURNOUT_RISK] = (
            _high(burn),
            f"Elevated burnout risk ({burn:.0f}).",
        )

    if rec is not None:
        scores[LifeState.RECOVERY_MODE] = (
            _and(_low(rec), _low_or_none(exe)),
            f"Low recovery ({rec:.0f}) with light output — body in recovery.",
        )

    if focus is not None and exe is not None:
        # Learning: focused but low raw execution output (reading/exploring).
        scores[LifeState.LEARNING_MODE] = (
            _and(_unit(focus), _mid(exe)),
            "High self-reported focus with moderate execution.",
        )

    if not scores:
        return StateResult(LifeState.UNKNOWN, 0.0, "Insufficient data to classify the day.")

    best_state = max(scores, key=lambda s: scores[s][0])
    confidence, rationale = scores[best_state]
    if confidence < 0.2:
        return StateResult(LifeState.UNKNOWN, round(confidence, 2), "Signals are weak/mixed.")
    return StateResult(best_state, round(confidence, 2), rationale)


# --- soft membership helpers (return 0..1) -------------------------------------------------

def _high(v: float | None) -> float:
    if v is None:
        return 0.0
    return _ramp(v, 60, 85)


def _low(v: float | None) -> float:
    if v is None:
        return 0.0
    return _ramp(v, 40, 15)


def _mid(v: float | None) -> float:
    if v is None:
        return 0.0
    return 1.0 - abs(v - 50) / 50.0


def _mid_or_low(v: float | None) -> float:
    if v is None:
        return 0.6
    return max(_mid(v), _low(v))


def _low_or_none(v: float | None) -> float:
    if v is None:
        return 0.6
    return _low(v)


def _ok_capacity(recovery: float | None, sleep: float | None, fitness: float | None = None) -> float:
    """Did the body have capacity to act? Recovery, sleep, and movement together.

    A day where you were recovered, rested, *and* physically active but produced
    nothing reads more clearly as avoidance than low output on a depleted day.
    """
    caps = [c for c in (recovery, sleep, fitness) if c is not None]
    if not caps:
        return 0.5
    return _ramp(sum(caps) / len(caps), 45, 70)


def _unit(likert: int | None) -> float:
    if likert is None:
        return 0.0
    return (likert - 1) / 4.0


def _ramp(v: float, lo: float, hi: float) -> float:
    """Linear membership: 0 at lo, 1 at hi (handles hi<lo for descending ramps)."""
    if hi == lo:
        return 1.0 if v >= hi else 0.0
    t = (v - lo) / (hi - lo)
    return max(0.0, min(1.0, t))


def _and(*xs: float) -> float:
    """Soft AND = product, but kept readable for two-three terms."""
    result = 1.0
    for x in xs:
        result *= x
    return result
