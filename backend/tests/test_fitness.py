"""Tests for the fitness score (physical activity as a life driver)."""

from __future__ import annotations

from app.services.scoring import FitnessInputs, fitness_score


def test_fitness_none_when_no_activity_data():
    # No workout, no steps, no calories synced => None (not a sedentary zero).
    assert fitness_score(FitnessInputs()) is None


def test_fitness_full_training_day_scores_high():
    # 60 min workout + 12k steps + 800 kcal — all components saturate.
    score = fitness_score(FitnessInputs(workout_minutes=60, steps=12000, active_calories=800))
    assert score == 100.0


def test_fitness_synced_but_sedentary_scores_low():
    # Data was synced (steps present) but the day was sedentary => real low signal.
    score = fitness_score(FitnessInputs(workout_minutes=0, steps=500, active_calories=80))
    assert score is not None
    assert score < 20.0


def test_fitness_workout_dominates():
    # A 45-min workout with few steps still scores well — training is weighted highest.
    workout_day = fitness_score(FitnessInputs(workout_minutes=45, steps=1000))
    steps_only = fitness_score(FitnessInputs(workout_minutes=0, steps=1000))
    assert workout_day is not None and steps_only is not None
    assert workout_day > steps_only
    # 0.55 workout (100) + 0.30 steps (10) = 58 baseline before calories.
    assert workout_day >= 55.0


def test_fitness_partial_data_steps_only():
    # 10k steps, no workout/calories logged => steps component only.
    score = fitness_score(FitnessInputs(steps=10000))
    assert score == 30.0  # 0.30 * 100


def test_fitness_clamps_excess():
    # Way past saturation still clamps to 100.
    score = fitness_score(FitnessInputs(workout_minutes=300, steps=50000, active_calories=5000))
    assert score == 100.0
