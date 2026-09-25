"""Unit tests for the pattern discovery engine."""

from __future__ import annotations

from app.services import correlation as corr


def test_positive_correlation_detected():
    driver = [float(i) for i in range(14)]
    outcome = [v * 2 + 1 for v in driver]  # perfectly correlated
    c = corr.lagged_correlation(driver, outcome, "sleep_score", "execution_score", min_samples=7)
    assert c is not None
    assert c.r > 0.95
    assert c.lag_days == 0
    assert c.n == 14


def test_lag_is_discovered():
    # outcome follows driver by 2 days.
    base = [10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 30, 20, 10, 5]
    driver = base
    outcome = [None, None] + base[:-2]
    c = corr.lagged_correlation(driver, outcome, "sleep_score", "mood", max_lag=3, min_samples=7)
    assert c is not None
    assert c.lag_days == 2


def test_insufficient_samples_returns_none():
    driver = [1.0, 2.0, 3.0]
    outcome = [1.0, 2.0, 3.0]
    assert corr.lagged_correlation(driver, outcome, "a", "b", min_samples=7) is None


def test_describe_includes_uplift_when_possible():
    driver = [float(i) for i in range(14)]
    outcome = [v * 2 for v in driver]
    c = corr.lagged_correlation(driver, outcome, "sleep_score", "execution_score", min_samples=7)
    text = corr.describe(c, driver, outcome).lower()
    assert "sleep score" in text
    assert "execution score" in text
    assert "%" in text  # concrete uplift rendered

