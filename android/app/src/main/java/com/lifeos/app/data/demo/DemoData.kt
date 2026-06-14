package com.lifeos.app.data.demo

import com.lifeos.app.BuildConfig
import com.lifeos.app.domain.model.AppNotification
import com.lifeos.app.domain.model.DailyScore
import com.lifeos.app.domain.model.DailyState
import com.lifeos.app.domain.model.DayPulse
import com.lifeos.app.domain.model.Insight
import java.time.LocalDate

object DemoMode {
    val active: Boolean get() = BuildConfig.USE_DEMO_DATA
}

object DemoData {
    private val today = LocalDate.now()

    val todayPulse: DayPulse = DayPulse.FLOW

    val scores: List<DailyScore> = listOf(
        DailyScore(today, sleepScore = 82.0, recoveryScore = 78.0, executionScore = 71.0, consistencyScore = 68.0, burnoutRisk = 22.0, wellnessScore = 80.0, fitnessScore = 76.0),
        DailyScore(today.minusDays(1), sleepScore = 74.0, recoveryScore = 70.0, executionScore = 85.0, consistencyScore = 72.0, burnoutRisk = 28.0, wellnessScore = 72.0, fitnessScore = 88.0),
        DailyScore(today.minusDays(2), sleepScore = 58.0, recoveryScore = 52.0, executionScore = 34.0, consistencyScore = 45.0, burnoutRisk = 61.0, wellnessScore = 40.0, fitnessScore = 18.0),
        DailyScore(today.minusDays(3), sleepScore = 65.0, recoveryScore = 60.0, executionScore = 55.0, consistencyScore = 58.0, burnoutRisk = 40.0, wellnessScore = 58.0, fitnessScore = 45.0),
    )

    val state: DailyState = DailyState(
        day = today,
        state = "peak_performance",
        confidence = 0.82,
        rationale = "High recovery (78) paired with strong execution (71).",
    )

    val notifications: List<AppNotification> = listOf(
        AppNotification(
            severity = "positive",
            title = "You are entering a high-performance phase",
            detail = "High recovery and execution detected. Good window for deep work.",
        ),
        AppNotification(
            severity = "warning",
            title = "Sleep debt accumulated 2 days ago",
            detail = "Sleep score dropped to 58. Recovery and focus typically follow sleep.",
        ),
    )

    val insights: List<Insight> = listOf(
        Insight(
            driver = "sleep_score",
            outcome = "execution_score",
            correlation = 0.68,
            sampleSize = 21,
            lagDays = 0,
            summary = "Execution Score increases as sleep score rises — r=+0.68 over 21 days. Execution Score increases 31% when sleep score is above 7.2h.",
        ),
        Insight(
            driver = "recovery_score",
            outcome = "mood",
            correlation = 0.54,
            sampleSize = 18,
            lagDays = 1,
            summary = "Mood increases as recovery score rises (with a 1-day lag) — r=+0.54 over 18 days.",
        ),
        Insight(
            driver = "execution_score",
            outcome = "mood",
            correlation = 0.47,
            sampleSize = 18,
            lagDays = 0,
            summary = "Mood increases as execution score rises — r=+0.47 over 18 days.",
        ),
    )

    val chatWelcome = """
        Based on your last 30 days: avg sleep score 76, recovery 71, execution 64.
        Current state: Peak Performance (82% confidence).
        Strongest pattern: execution rises 31% when sleep exceeds ~7.2 hours.
        What would you like to explore?
    """.trim()
}
