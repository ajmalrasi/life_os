"""Pattern discovery engine.

Computes lagged Pearson correlations between "driver" series (e.g. sleep) and
"outcome" series (e.g. execution / mood), then renders the strongest, most
sample-backed relationships as human-readable insights.

Pure/stateless — the orchestration layer feeds it aligned daily series.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np


@dataclass
class Correlation:
    driver: str
    outcome: str
    r: float
    n: int
    lag_days: int


def _pearson(x: list[float], y: list[float]) -> float | None:
    if len(x) != len(y) or len(x) < 3:
        return None
    xa = np.asarray(x, dtype=float)
    ya = np.asarray(y, dtype=float)
    if np.std(xa) == 0 or np.std(ya) == 0:
        return None
    r = float(np.corrcoef(xa, ya)[0, 1])
    if np.isnan(r):
        return None
    return r


def lagged_correlation(
    driver: list[float | None],
    outcome: list[float | None],
    driver_name: str,
    outcome_name: str,
    max_lag: int = 3,
    min_samples: int = 7,
) -> Correlation | None:
    """Best correlation of driver[t] vs outcome[t+lag] for lag in 0..max_lag.

    Both lists are daily-aligned and equal length; ``None`` marks missing days
    and is dropped pairwise.
    """
    best: Correlation | None = None
    n_days = len(driver)
    for lag in range(0, max_lag + 1):
        xs: list[float] = []
        ys: list[float] = []
        for t in range(n_days - lag):
            d = driver[t]
            o = outcome[t + lag]
            if d is None or o is None:
                continue
            xs.append(float(d))
            ys.append(float(o))
        if len(xs) < min_samples:
            continue
        r = _pearson(xs, ys)
        if r is None:
            continue
        if best is None or abs(r) > abs(best.r):
            best = Correlation(driver_name, outcome_name, round(r, 3), len(xs), lag)
    return best


def threshold_uplift(
    driver: list[float | None],
    outcome: list[float | None],
    split: float,
) -> tuple[float, float] | None:
    """Mean outcome when driver >= split vs < split. Returns (high_mean, low_mean)."""
    high: list[float] = []
    low: list[float] = []
    for d, o in zip(driver, outcome):
        if d is None or o is None:
            continue
        (high if d >= split else low).append(float(o))
    if len(high) < 2 or len(low) < 2:
        return None
    return (sum(high) / len(high), sum(low) / len(low))


def describe(corr: Correlation, driver_series: list[float | None], outcome_series: list[float | None]) -> str:
    """Render a plain-language insight, with a concrete uplift % when possible."""
    direction = "increases" if corr.r > 0 else "decreases"
    label_driver = _pretty(corr.driver)
    label_outcome = _pretty(corr.outcome)

    # Try a threshold split at the median of the driver to produce an uplift %.
    vals = [v for v in driver_series if v is not None]
    detail = ""
    if vals:
        median = float(np.median(vals))
        up = threshold_uplift(driver_series, outcome_series, median)
        if up:
            high_mean, low_mean = up
            if low_mean > 0:
                pct = (high_mean - low_mean) / low_mean * 100.0
                detail = (
                    f" {label_outcome} {('increases' if pct >= 0 else 'decreases')} "
                    f"{abs(pct):.0f}% when {label_driver.lower()} is above {median:.1f}."
                )

    lag_txt = "" if corr.lag_days == 0 else f" (with a {corr.lag_days}-day lag)"
    return (
        f"{label_outcome} {direction} as {label_driver.lower()} rises"
        f"{lag_txt} — r={corr.r:+.2f} over {corr.n} days.{detail}"
    )


def _pretty(name: str) -> str:
    return name.replace("_", " ").title()
