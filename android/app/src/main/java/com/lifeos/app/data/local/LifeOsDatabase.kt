package com.lifeos.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        MoodEntity::class, ScoreEntity::class, StateEntity::class, InsightEntity::class,
        SynthesizedInsightEntity::class, TransactionEntity::class, CardStatementEntity::class,
        CurrentCardCycleEntity::class,
    ],
    version = 12, // v12: exact tracked card-cycle spend; no forecasts
    exportSchema = false,
)
abstract class LifeOsDatabase : RoomDatabase() {
    abstract fun moodDao(): MoodDao
    abstract fun scoreDao(): ScoreDao
    abstract fun stateDao(): StateDao
    abstract fun insightDao(): InsightDao
    abstract fun synthesizedInsightDao(): SynthesizedInsightDao
    abstract fun transactionDao(): TransactionDao
    abstract fun cardStatementDao(): CardStatementDao
    abstract fun currentCardCycleDao(): CurrentCardCycleDao
}
