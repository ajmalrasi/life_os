package com.lifeos.app.trips.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TripEntity::class, TripDayEntity::class, TripPlaceEntity::class,
        TripActivityEntity::class, TripReminderEntity::class, TripVisitEntity::class,
        TripLocationSampleEntity::class, TripExpenseEntity::class, TripAttachmentEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class TripDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trip_activities ADD COLUMN fromPlaceId TEXT")
                db.execSQL("ALTER TABLE trip_activities ADD COLUMN toPlaceId TEXT")
                db.execSQL("ALTER TABLE trip_activities ADD COLUMN stepsJson TEXT")
            }
        }
    }
}
