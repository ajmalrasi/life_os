package com.lifeos.app.places.data

import androidx.room.Database
import androidx.room.RoomDatabase

/** Separate from the main cache, like Trips: this is the user's own record, never refetchable. */
@Database(
    entities = [PlaceSampleEntity::class, PlaceStopEntity::class, SavedPlaceEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class PlacesDatabase : RoomDatabase() {
    abstract fun placesDao(): PlacesDao
}
