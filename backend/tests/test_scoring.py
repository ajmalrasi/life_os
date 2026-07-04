"""Unit tests for the pure scoring engine."""

from __future__ import annotations

from app.services import scoring


def test_sleep_score_peaks_near_eight_hours():
    eight = scoring.sleep_score(scoring.SleepInputs(total_hours=8.0))
    five = scoring.sleep_score(scoring.SleepInputs(total_hours=5.0))
    eleven = scoring.sleep_score(scoring.SleepInputs(total_hours=11.0))
    assert eight == 100.0
    assert five < eight
    assert eleven < eight


def test_sleep_score_none_without_data():
    assert scoring.sleep_score(scoring.SleepInputs(total_hours=None)) is None


def test_sleep_score_stage_quality_and_fragmentation():
    good = scoring.sleep_score(
        scoring.SleepInputs(total_hours=8.0, deep_hours=1.5, rem_hours=1.8, awake_hours=0.1)
    )
    fragmented = scoring.sleep_score(
        scoring.SleepInputs(total_hours=8.0, deep_hours=1.5, rem_hours=1.8, awake_hours=1.5)
    )
    assert good > fragmented


def test_execution_score_zero_is_meaningful():
    assert scoring.execution_score(scoring.ExecutionInputs()) == 0.0


def test_execution_score_scales_with_output():
    low = scoring.execution_score(scoring.ExecutionInputs(commits=1, coding_minutes=20))
    high = scoring.execution_score(
        scoring.ExecutionInputs(commits=8, coding_minutes=240, active_repos=3)
    )
    assert 0 < low < high <= 100


def test_execution_score_pr_issue_activity_alone_is_nonzero():
    assert scoring.execution_score(scoring.ExecutionInputs(pr_issue_activity=2)) > 0.0


def test_execution_score_pr_issue_activity_boosts_score():
    without = scoring.execution_score(scoring.ExecutionInputs(commits=2, coding_minutes=30))
    with_activity = scoring.execution_score(
        scoring.ExecutionInputs(commits=2, coding_minutes=30, pr_issue_activity=3)
    )
    assert with_activity > without


def test_recovery_uses_baseline_direction():
    above = scoring.recovery_score(
        scoring.RecoveryInputs(hrv_ms=80, hrv_baseline_ms=60)
    )
    below = scoring.recovery_score(
        scoring.RecoveryInputs(hrv_ms=40, hrv_baseline_ms=60)
    )
    assert above > below


def test_recovery_none_without_inputs():
    assert scoring.recovery_score(scoring.RecoveryInputs()) is None


def test_consistency_high_when_steady():
    steady = scoring.consistency_score([70, 72, 68, 71, 69])
    erratic = scoring.consistency_score([0, 100, 0, 95, 5])
    assert steady is not None and erratic is not None
    assert steady > erratic


def test_burnout_high_with_low_recovery_high_output_high_stress():
    risk = scoring.burnout_risk(
        recovery_history=[30, 35, 28],
        execution_history=[90, 95, 88],
        stress_history=[5, 5, 4],
    )
    assert risk is not None and risk >= 60
