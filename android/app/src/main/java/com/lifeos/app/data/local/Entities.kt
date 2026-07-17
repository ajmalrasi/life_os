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
    val fitnessScore: Double? = null,
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
    val narrative: String? = null,
)

@Entity(tableName = "synthesized_insights")
data class SynthesizedInsightEntity(
    @PrimaryKey val rank: Int, // display order; the batch is replaced wholesale
    val title: String,
    val body: String,
    val theme: String?,
    val cites: String, // comma-joined evidence fact ids
)

@Entity(tableName = "transactions_cache")
data class TransactionEntity(
    @PrimaryKey val id: Int,
    val txnDate: String,
    val txnAt: String?, // ISO-8601 instant; null when the txn has no known time
    val amount: String, // BigDecimal-precision string, parsed on read
    val currency: String,
    val direction: String,
    val counterparty: String?,
    val category: String?,
    val spendCategory: String?,
    val account: String?,
    val source: String,
    val reference: String?,
)

@Entity(tableName = "card_statements_cache")
data class CardStatementEntity(
    @PrimaryKey val id: Int,
    val bank: String,
    val cardLabel: String,
    val cardLast4: String?,
    val statementDate: String?,
    val periodStart: String?,
    val periodEnd: String?,
    val dueDate: String?,
    val totalDue: String?,
    val minDue: String?,
    val totalSpend: String?,
    val paidAt: String?,
)
