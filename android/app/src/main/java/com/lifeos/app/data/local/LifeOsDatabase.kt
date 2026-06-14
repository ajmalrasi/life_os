package com.lifeos.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MoodEntity::class, ScoreEntity::class, StateEntity::class, InsightEntity::class],
    version = 2, // v2: added ScoreEntity.wellnessScore (destructive migration is configured)
    exportSchema = false,
)
abstract class LifeOsDatabase : RoomDatabase() {
    abstract fun moodDao(): MoodDao
    abstract fun scoreDao(): ScoreDao
    abstract fun stateDao(): StateDao
    abstract fun insightDao(): InsightDao
}
