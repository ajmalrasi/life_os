package com.lifeos.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mood_checkins")
data class MoodEntity(
    @PrimaryKey val day: String,
    val mood: Int,
    val energy: Int,
    val focus: Int,
    val stress: Int,
    val note: String?,
)

@Entity(tableName = "daily_scores")
data class ScoreEntity(
    @PrimaryKey val day: String,
    val sleepScore: Double?,
    val recoveryScore: Double?,
    val executionScore: Double?,
    val consistencyScore: Double?,
    val burnoutRisk: Double?,
    val wellnessScore: Double? = null,
)

@Entity(tableName = "daily_states")
data class StateEntity(
    @PrimaryKey val day: String,
    val state: String,
    val confidence: Double,
    val rationale: String?,
)

@Entity(tableName = "insights")
data class InsightEntity(
    @PrimaryKey val pair: String, // "driver|outcome"
    val driver: String,
    val outcome: String,
    val correlation: Double,
    val sampleSize: Int,
    val lagDays: Int,
    val summary: String,
)
