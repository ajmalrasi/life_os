package com.lifeos.app.data.demo

import com.lifeos.app.BuildConfig
import com.lifeos.app.domain.model.AppNotification
import com.lifeos.app.domain.model.DailyScore
import com.lifeos.app.domain.model.DailyState
import com.lifeos.app.domain.model.DayPulse
import com.lifeos.app.domain.model.SynthesizedInsight
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

    val synthesizedInsights: List<SynthesizedInsight> = listOf(
        SynthesizedInsight(
            title = "Shopping is running 3x usual",
            body = "You spent ₹25,000 on shopping this month, which is 30% of your spend and 3x your usual ₹8,000. That's too much for a single category.",
            action = "Cap shopping at ₹10,000 for the rest of the month.",
            theme = "money",
            severity = "high",
            cites = listOf("cat:shopping"),
        ),
        SynthesizedInsight(
            title = "Vehicle maintenance beats food",
            body = "Vehicle maintenance (₹6,000) is now 1.7x what you spend on food (₹3,500) this month.",
            action = "Get a written quote before the next repair.",
            theme = "money",
            severity = "medium",
            cites = listOf("cmp:vehicle_vs_food"),
        ),
        SynthesizedInsight(
            title = "Sleep is holding steady",
            body = "Sleep score averaged 78 this week, level with last week, and recovery is climbing alongside it.",
            action = "Keep the current bedtime routine.",
            theme = "health",
            severity = "low",
            cites = listOf("health:sleep", "health:recovery"),
        ),
    )

    val chatWelcome = """
        Based on your last 30 days: avg sleep score 76, recovery 71, execution 64.
        Current state: Peak Performance (82% confidence).
        Strongest pattern: execution rises 31% when sleep exceeds ~7.2 hours.
        What would you like to explore?
    """.trim()
}
