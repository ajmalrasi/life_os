package com.lifeos.app.trips.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TripEntity::class, TripDayEntity::class, TripPlaceEntity::class,
        TripActivityEntity::class, TripReminderEntity::class, TripVisitEntity::class,
        TripLocationSampleEntity::class, TripExpenseEntity::class, TripAttachmentEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class TripDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
}
