package com.lifeos.app.places.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lifeos.app.trips.location.LocationFix
import com.lifeos.app.trips.location.StopSpan

/** A raw position: the route between stops, kept on the phone until the user deletes history, so it can be mapped later. */
@Entity(tableName = "place_samples", indices = [Index("recordedAt")])
data class PlaceSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    override val latitude: Double,
    override val longitude: Double,
    override val accuracyMeters: Float,
    val speedMetersPerSecond: Float?,
    override val recordedAt: Long,
) : LocationFix

/** A stay somewhere. [nameSource] says where [name] came from, so only
 * placeholder and address names are replaced when a saved place is set. */
@Entity(tableName = "place_stops", indices = [Index("arrivedAt")])
data class PlaceStopEntity(
    @PrimaryKey val id: String,
    override val latitude: Double,
    override val longitude: Double,
    val name: String,
    val nameSource: String = NameSource.PENDING,
    override val arrivedAt: Long,
    override val departedAt: Long? = null,
    val savedPlaceId: String? = null,
) : StopSpan

object NameSource {
    const val PENDING = "pending"
    const val ADDRESS = "address"
    const val SAVED = "saved"
    const val USER = "user"
}

/** Home, work, or a spot the user said is not home (so it is not suggested again). */
@Entity(tableName = "saved_places")
data class SavedPlaceEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

object SavedPlaceKind {
    const val HOME = "home"
    const val WORK = "work"
    const val NOT_HOME = "not_home"
}
