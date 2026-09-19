package com.lifeos.app.trips.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class TripDetail(
    @androidx.room.Embedded val trip: TripEntity,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "tripId") val days: List<TripDayEntity>,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "tripId") val places: List<TripPlaceEntity>,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "tripId") val activities: List<TripActivityEntity>,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "tripId") val reminders: List<TripReminderEntity>,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "tripId") val visits: List<TripVisitEntity>,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "tripId") val expenses: List<TripExpenseEntity>,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "tripId") val attachments: List<TripAttachmentEntity>,
)

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startDate DESC")
    fun observeTrips(): Flow<List<TripEntity>>

    @Transaction
    @Query("SELECT * FROM trips WHERE id = :tripId LIMIT 1")
    fun observeTrip(tripId: String): Flow<TripDetail?>

    @Transaction
    @Query("SELECT * FROM trips WHERE id = :tripId LIMIT 1")
    suspend fun getTrip(tripId: String): TripDetail?

    @Query("SELECT * FROM trips WHERE status = 'active' LIMIT 1")
    suspend fun activeTrip(): TripEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTrip(value: TripEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertDays(values: List<TripDayEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPlaces(values: List<TripPlaceEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPlace(value: TripPlaceEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertActivities(values: List<TripActivityEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertActivity(value: TripActivityEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertReminders(values: List<TripReminderEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertExpense(value: TripExpenseEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAttachment(value: TripAttachmentEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLocation(value: TripLocationSampleEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertVisit(value: TripVisitEntity)

    @Query("UPDATE trips SET status = :status, updatedAt = :updatedAt WHERE id = :tripId")
    suspend fun setStatus(tripId: String, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query("""
        UPDATE trips SET title = :title, startDate = :startDate, endDate = :endDate,
            timezone = :timezone, currency = :currency, originalText = :originalText,
            updatedAt = :updatedAt
        WHERE id = :tripId
    """)
    suspend fun updateImportedTrip(
        tripId: String,
        title: String,
        startDate: String,
        endDate: String,
        timezone: String,
        currency: String,
        originalText: String?,
        updatedAt: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE trip_activities SET status = :status WHERE id = :activityId")
    suspend fun setActivityStatus(activityId: String, status: String)

    @Query("UPDATE trip_reminders SET enabled = :enabled WHERE id = :reminderId")
    suspend fun setReminderEnabled(reminderId: String, enabled: Boolean)

    @Query("UPDATE trip_places SET latitude = :latitude, longitude = :longitude, verification = 'supplied' WHERE id = :placeId")
    suspend fun resolvePlace(placeId: String, latitude: Double, longitude: Double)

    @Query("DELETE FROM trip_places WHERE id = :placeId")
    suspend fun deletePlace(placeId: String)

    @Query("UPDATE trip_activities SET placeId = NULL WHERE placeId = :placeId")
    suspend fun clearPlaceReferences(placeId: String)

    @Transaction
    suspend fun removePlace(placeId: String) {
        clearPlaceReferences(placeId)
        deletePlace(placeId)
    }

    @Query("SELECT * FROM trip_activities WHERE dayId = :dayId ORDER BY sortOrder")
    suspend fun activitiesForDay(dayId: String): List<TripActivityEntity>

    @Query("DELETE FROM trip_activities WHERE id = :activityId")
    suspend fun deleteActivity(activityId: String)

    @Query("SELECT COUNT(*) FROM trip_activities WHERE placeId = :placeId")
    suspend fun placeReferenceCount(placeId: String): Int

    @Transaction
    suspend fun saveScheduledStop(
        place: TripPlaceEntity,
        activity: TripActivityEntity,
        previousDayId: String?,
        position: Int,
    ) {
        upsertPlace(place)
        if (previousDayId != null && previousDayId != activity.dayId) {
            val oldDay = activitiesForDay(previousDayId).filterNot { it.id == activity.id }
            upsertActivities(oldDay.mapIndexed { index, item -> item.copy(sortOrder = index) })
        }
        val target = activitiesForDay(activity.dayId).filterNot { it.id == activity.id }.toMutableList()
        target.add(position.coerceIn(0, target.size), activity)
        upsertActivities(target.mapIndexed { index, item -> item.copy(sortOrder = index) })
    }

    @Transaction
    suspend fun removeScheduledStop(activityId: String, placeId: String?) {
        val activity = activitiesForDayForActivity(activityId)
        deleteActivity(activityId)
        if (activity != null) {
            val remaining = activitiesForDay(activity.dayId)
            upsertActivities(remaining.mapIndexed { index, item -> item.copy(sortOrder = index) })
        }
        if (placeId != null && placeReferenceCount(placeId) == 0) deletePlace(placeId)
    }

    @Query("SELECT * FROM trip_activities WHERE id = :activityId LIMIT 1")
    suspend fun activitiesForDayForActivity(activityId: String): TripActivityEntity?

    @Query("SELECT * FROM trip_location_samples WHERE tripId = :tripId ORDER BY recordedAt")
    fun observeLocations(tripId: String): Flow<List<TripLocationSampleEntity>>

    @Query("SELECT * FROM trip_location_samples WHERE tripId = :tripId ORDER BY recordedAt")
    suspend fun getLocations(tripId: String): List<TripLocationSampleEntity>

    @Query("UPDATE trips SET backupRevision = :revision, lastBackedUpAt = :at WHERE id = :tripId")
    suspend fun markBackedUp(tripId: String, revision: Int, at: Long = System.currentTimeMillis())

    @Query("SELECT * FROM trip_location_samples WHERE tripId = :tripId AND recordedAt >= :since ORDER BY recordedAt")
    suspend fun recentLocations(tripId: String, since: Long): List<TripLocationSampleEntity>

    @Query("SELECT * FROM trip_visits WHERE tripId = :tripId AND departedAt IS NULL ORDER BY arrivedAt DESC LIMIT 1")
    suspend fun openVisit(tripId: String): TripVisitEntity?

    @Query("DELETE FROM trips WHERE id = :tripId")
    suspend fun deleteTrip(tripId: String)

    @Query("DELETE FROM trip_activities WHERE tripId = :tripId")
    suspend fun deleteTripActivities(tripId: String)

    @Query("DELETE FROM trip_reminders WHERE tripId = :tripId")
    suspend fun deleteTripReminders(tripId: String)

    @Query("DELETE FROM trip_days WHERE tripId = :tripId")
    suspend fun deleteTripDays(tripId: String)

    @Query("DELETE FROM trip_places WHERE tripId = :tripId")
    suspend fun deleteTripPlaces(tripId: String)

    @Transaction
    suspend fun importTrip(bundle: ImportedTripBundle) {
        if (insertTrip(bundle.trip) == -1L) {
            updateImportedTrip(
                bundle.trip.id, bundle.trip.title, bundle.trip.startDate, bundle.trip.endDate,
                bundle.trip.timezone, bundle.trip.currency, bundle.trip.originalText,
            )
        }
        upsertDays(bundle.days)
        upsertPlaces(bundle.places)
        upsertActivities(bundle.activities)
        upsertReminders(bundle.reminders)
    }

    @Transaction
    suspend fun replaceTripItinerary(bundle: ImportedTripBundle) {
        if (insertTrip(bundle.trip) == -1L) {
            updateImportedTrip(
                bundle.trip.id, bundle.trip.title, bundle.trip.startDate, bundle.trip.endDate,
                bundle.trip.timezone, bundle.trip.currency, bundle.trip.originalText,
            )
        }
        deleteTripActivities(bundle.trip.id)
        deleteTripReminders(bundle.trip.id)
        deleteTripDays(bundle.trip.id)
        deleteTripPlaces(bundle.trip.id)
        upsertDays(bundle.days)
        upsertPlaces(bundle.places)
        upsertActivities(bundle.activities)
        upsertReminders(bundle.reminders)
    }
}

data class ImportedTripBundle(
    val trip: TripEntity,
    val days: List<TripDayEntity>,
    val places: List<TripPlaceEntity>,
    val activities: List<TripActivityEntity>,
    val reminders: List<TripReminderEntity>,
)
