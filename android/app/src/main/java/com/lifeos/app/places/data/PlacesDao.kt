package com.lifeos.app.places.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlacesDao {
    @Insert suspend fun insertSample(value: PlaceSampleEntity)

    @Query("SELECT * FROM place_samples ORDER BY recordedAt DESC LIMIT 1")
    suspend fun lastSample(): PlaceSampleEntity?

    @Query("SELECT * FROM place_samples WHERE recordedAt >= :since ORDER BY recordedAt")
    suspend fun samplesSince(since: Long): List<PlaceSampleEntity>

    @Query("SELECT * FROM place_samples WHERE recordedAt >= :start AND recordedAt < :end ORDER BY recordedAt")
    fun observeSamplesBetween(start: Long, end: Long): Flow<List<PlaceSampleEntity>>

    @Query("SELECT * FROM place_samples WHERE recordedAt >= :start AND recordedAt < :end ORDER BY recordedAt")
    suspend fun samplesBetween(start: Long, end: Long): List<PlaceSampleEntity>

    @Query("SELECT * FROM place_stops WHERE departedAt IS NULL ORDER BY arrivedAt DESC LIMIT 1")
    suspend fun openStop(): PlaceStopEntity?

    @Query("SELECT * FROM place_stops WHERE departedAt IS NULL OR departedAt >= :since ORDER BY arrivedAt")
    suspend fun stopsSince(since: Long): List<PlaceStopEntity>

    /** Stops that overlap [start, end). */
    @Query("SELECT * FROM place_stops WHERE arrivedAt < :end AND (departedAt IS NULL OR departedAt >= :start) ORDER BY arrivedAt")
    fun observeStopsBetween(start: Long, end: Long): Flow<List<PlaceStopEntity>>

    @Query("SELECT * FROM place_stops WHERE arrivedAt < :end AND (departedAt IS NULL OR departedAt >= :start) ORDER BY arrivedAt")
    suspend fun stopsBetween(start: Long, end: Long): List<PlaceStopEntity>

    @Query("SELECT * FROM place_stops WHERE nameSource = 'pending'")
    suspend fun pendingNameStops(): List<PlaceStopEntity>

    @Query("SELECT * FROM place_stops WHERE id = :id")
    suspend fun getStop(id: String): PlaceStopEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertStop(value: PlaceStopEntity)

    @Query("DELETE FROM place_stops WHERE id = :id")
    suspend fun deleteStop(id: String)

    @Query("SELECT * FROM saved_places")
    fun observeSavedPlaces(): Flow<List<SavedPlaceEntity>>

    @Query("SELECT * FROM saved_places")
    suspend fun savedPlaces(): List<SavedPlaceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSavedPlace(value: SavedPlaceEntity)

    @Query("DELETE FROM saved_places WHERE kind = :kind")
    suspend fun deleteSavedPlaces(kind: String)

    @Query("DELETE FROM place_samples") suspend fun deleteAllSamples()
    @Query("DELETE FROM place_stops") suspend fun deleteAllStops()
    @Query("DELETE FROM saved_places") suspend fun deleteAllSavedPlaces()
}
