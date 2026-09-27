package com.lifeos.app.trips.data

import com.lifeos.app.trips.location.LocationFix
import com.lifeos.app.trips.location.StopSpan

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey val id: String,
    val title: String,
    val startDate: String,
    val endDate: String,
    val timezone: String,
    val currency: String,
    val status: String = "draft",
    val originalText: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val backupRevision: Int? = null,
    val lastBackedUpAt: Long? = null,
)

@Entity(
    tableName = "trip_days",
    foreignKeys = [ForeignKey(TripEntity::class, ["id"], ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class TripDayEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val date: String,
    val title: String,
    val sortOrder: Int,
)

@Entity(
    tableName = "trip_places",
    foreignKeys = [ForeignKey(TripEntity::class, ["id"], ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class TripPlaceEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val name: String,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val googlePlaceId: String? = null,
    val kind: String = "attraction",
    val verification: String = "unresolved",
)

@Entity(
    tableName = "trip_activities",
    foreignKeys = [ForeignKey(TripEntity::class, ["id"], ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId"), Index("dayId"), Index("placeId")],
)
data class TripActivityEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val dayId: String,
    val title: String,
    val type: String,
    val startTime: String? = null,
    val endTime: String? = null,
    val durationMinutes: Int? = null,
    val placeId: String? = null,
    val fromPlaceId: String? = null,
    val toPlaceId: String? = null,
    val notes: String? = null,
    val flexibility: String = "flexible",
    val condition: String? = null,
    val transportMode: String? = null,
    val stepsJson: String? = null,
    val status: String = "planned",
    val sortOrder: Int,
)

@Entity(
    tableName = "trip_reminders",
    foreignKeys = [ForeignKey(TripEntity::class, ["id"], ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId"), Index("activityId")],
)
data class TripReminderEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val activityId: String? = null,
    val title: String,
    val note: String? = null,
    val localDateTime: String? = null,
    val enabled: Boolean = false,
)

@Entity(
    tableName = "trip_visits",
    foreignKeys = [ForeignKey(TripEntity::class, ["id"], ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId"), Index("placeId")],
)
data class TripVisitEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val placeId: String? = null,
    val name: String,
    override val latitude: Double,
    override val longitude: Double,
    override val arrivedAt: Long,
    override val departedAt: Long? = null,
    val confirmed: Boolean = false,
    val note: String? = null,
) : StopSpan

@Entity(
    tableName = "trip_location_samples",
    foreignKeys = [ForeignKey(TripEntity::class, ["id"], ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId"), Index(value = ["tripId", "recordedAt"])],
)
data class TripLocationSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: String,
    override val latitude: Double,
    override val longitude: Double,
    override val accuracyMeters: Float,
    val speedMetersPerSecond: Float?,
    override val recordedAt: Long,
) : LocationFix

@Entity(
    tableName = "trip_expenses",
    foreignKeys = [ForeignKey(TripEntity::class, ["id"], ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId"), Index("transactionId")],
)
data class TripExpenseEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val title: String,
    val amount: String,
    val currency: String,
    val category: String,
    val paidAt: String,
    val transactionId: Int? = null,
    val note: String? = null,
)

@Entity(
    tableName = "trip_attachments",
    foreignKeys = [ForeignKey(TripEntity::class, ["id"], ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId"), Index("visitId"), Index("activityId")],
)
data class TripAttachmentEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val visitId: String? = null,
    val activityId: String? = null,
    val kind: String,
    val displayName: String,
    val localUri: String,
    val note: String? = null,
)
