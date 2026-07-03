package com.lifeos.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        MoodEntity::class, ScoreEntity::class, StateEntity::class, InsightEntity::class,
        TransactionEntity::class, CardStatementEntity::class,
    ],
    version = 6, // v6: added TransactionEntity.spendCategory (destructive migration is configured)
    exportSchema = false,
)
abstract class LifeOsDatabase : RoomDatabase() {
    abstract fun moodDao(): MoodDao
    abstract fun scoreDao(): ScoreDao
    abstract fun stateDao(): StateDao
    abstract fun insightDao(): InsightDao
    abstract fun transactionDao(): TransactionDao
    abstract fun cardStatementDao(): CardStatementDao
}
