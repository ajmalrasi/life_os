package com.lifeos.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        MoodEntity::class, ScoreEntity::class, StateEntity::class, InsightEntity::class,
        SynthesizedInsightEntity::class, TransactionEntity::class, CardStatementEntity::class,
    ],
    version = 10, // v10: cache statement payment/detail parse state
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
}
