package com.lifeos.app.trips.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.lifeos.app.trips.data.TripAttachmentEntity
import com.lifeos.app.trips.data.TripActivityEntity
import com.lifeos.app.trips.data.TripDetail
import com.lifeos.app.trips.data.TripEntity
import com.lifeos.app.trips.data.TripExpenseEntity
import com.lifeos.app.trips.data.TripLocationSampleEntity
import com.lifeos.app.trips.data.TripFileStore
import com.lifeos.app.trips.data.TripPlaceEntity
import com.lifeos.app.trips.data.TripRepository
import com.lifeos.app.trips.importer.TripImportPreview
import com.lifeos.app.trips.importer.TripImporter
import com.lifeos.app.work.WorkScheduler
import android.content.Intent
import com.lifeos.app.trips.location.TripLocationService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

data class TripsUiState(
    val trips: List<TripEntity> = emptyList(),
    val preview: TripImportPreview? = null,
    val importError: String? = null,
    val importing: Boolean = false,
    val editedTitle: String = "",
    val asNewTrip: Boolean = true,
)

@HiltViewModel
class TripsViewModel @Inject constructor(
    private val repository: TripRepository,
    private val importer: TripImporter,
) : ViewModel() {
    private val controls = MutableStateFlow(TripsUiState())
    val state: StateFlow<TripsUiState> = combine(repository.observeTrips(), controls) { trips, state ->
        state.copy(trips = trips)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripsUiState())

    fun preview(raw: String) {
        controls.update { it.copy(importing = true, importError = null, preview = null) }
        runCatching { importer.parse(raw) }
            .onSuccess { result ->
                controls.update {
                    it.copy(
                        importing = false,
                        preview = result,
                        editedTitle = result.bundle.trip.title,
                        asNewTrip = true,
                    )
                }
            }
            .onFailure { error -> controls.update { it.copy(importing = false, importError = error.message) } }
    }

    fun setAsNewTrip(asNew: Boolean) = controls.update { it.copy(asNewTrip = asNew) }
    fun setEditedTitle(title: String) = controls.update { it.copy(editedTitle = title) }

    fun cancelPreview() = controls.update { it.copy(preview = null, importError = null) }

    fun deleteTrip(tripId: String) = viewModelScope.launch { repository.delete(tripId) }

    fun savePreview(onSaved: (String) -> Unit) {
        val current = controls.value
        val preview = current.preview ?: return
        val asNew = current.asNewTrip
        val title = current.editedTitle.trim().ifBlank { preview.bundle.trip.title }
        val targetTripId = if (asNew) UUID.randomUUID().toString() else preview.bundle.trip.id
        val bundle = preview.buildBundle(targetTripId, title)
        viewModelScope.launch {
            runCatching { repository.import(bundle, replaceExisting = !asNew) }
                .onSuccess {
                    controls.update { it.copy(preview = null, importError = null) }
                    onSaved(targetTripId)
                }
                .onFailure { error -> controls.update { it.copy(importError = error.message) } }
        }
    }
}

data class TripDetailUiState(
    val detail: TripDetail? = null,
    val locations: List<TripLocationSampleEntity> = emptyList(),
    val backupMessage: String? = null,
    val map: TripMapUiState = TripMapUiState(),
)

data class TripMapUiState(
    val resolving: Boolean = false,
    val resolvedCount: Int = 0,
    val totalCount: Int = 0,
    val currentLatitude: Double? = null,
    val currentLongitude: Double? = null,
    val currentLabel: String? = null,
)

@HiltViewModel
class TripDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val repository: TripRepository,
    private val fileStore: TripFileStore,
    private val workScheduler: WorkScheduler,
) : ViewModel() {
    private val backupMessage = MutableStateFlow<String?>(null)
    private val mapState = MutableStateFlow(TripMapUiState())
    val tripId: String = checkNotNull(savedStateHandle["tripId"])
    val state: StateFlow<TripDetailUiState> = combine(
        repository.observeTrip(tripId), repository.observeLocations(tripId), backupMessage, mapState,
    ) { detail, locations, message, map -> TripDetailUiState(detail, locations, message, map) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripDetailUiState())

    fun setStatus(status: String) = viewModelScope.launch { repository.setStatus(tripId, status) }
    fun setActivityStatus(id: String, status: String) = viewModelScope.launch {
        repository.setActivityStatus(id, status)
    }

    fun chooseAlternative(selectedId: String, siblingIds: List<String>) = viewModelScope.launch {
        siblingIds.forEach { id ->
            repository.setActivityStatus(id, if (id == selectedId) "completed" else "skipped")
        }
    }

    fun addExpense(title: String, amount: String, category: String, paidAt: String) = viewModelScope.launch {
        val currency = state.value.detail?.trip?.currency ?: "INR"
        repository.addExpense(
            TripExpenseEntity(UUID.randomUUID().toString(), tripId, title, amount, currency, category, paidAt)
        )
    }

    fun addAttachment(uri: String, kind: String = "photo") = viewModelScope.launch {
        val stored = fileStore.copyIntoTrip(tripId, android.net.Uri.parse(uri))
        repository.addAttachment(TripAttachmentEntity(
            UUID.randomUUID().toString(), tripId, kind = kind,
            displayName = stored.displayName, localUri = stored.uri,
        ))
    }

    fun delete(onDeleted: () -> Unit = {}) = viewModelScope.launch {
        if (state.value.detail?.trip?.status == "active") {
            context.startService(
                Intent(context, TripLocationService::class.java).setAction(TripLocationService.ACTION_STOP)
            )
        }
        repository.delete(tripId)
        onDeleted()
    }

    fun backup() = viewModelScope.launch {
        backupMessage.value = "Backing up…"
        backupMessage.value = runCatching { repository.backup(tripId) }
            .fold({ "Backed up revision $it" }, { it.message ?: "Backup failed" })
    }

    fun enableReminder(id: String) = viewModelScope.launch {
        val detail = state.value.detail ?: return@launch
        val reminder = detail.reminders.firstOrNull { it.id == id } ?: return@launch
        val raw = reminder.localDateTime ?: return@launch
        val instant = runCatching {
            LocalDateTime.parse(raw).atZone(ZoneId.of(detail.trip.timezone)).toInstant()
        }.getOrNull() ?: return@launch
        workScheduler.scheduleTripReminder(reminder.id, reminder.title, reminder.note.orEmpty(), instant)
        repository.setReminderEnabled(reminder.id, true)
    }

    fun resolvePlace(id: String, latitude: Double, longitude: Double) = viewModelScope.launch {
        repository.resolvePlace(id, latitude, longitude)
    }

    fun resolveItineraryPlaces() {
        val detail = state.value.detail ?: return
        val unresolved = detail.places.filter { it.latitude == null || it.longitude == null }
        if (unresolved.isEmpty() || mapState.value.resolving) return
        mapState.update { it.copy(resolving = true, resolvedCount = 0, totalCount = unresolved.size) }
        viewModelScope.launch(Dispatchers.IO) {
            val geocoder = Geocoder(context, Locale.getDefault())
            var resolved = 0
            unresolved.forEach { place ->
                val query = listOfNotNull(place.name, place.address, detail.trip.title, "India")
                    .distinct().joinToString(", ")
                val address = runCatching { geocoder.getFromLocationName(query, 1)?.firstOrNull() }.getOrNull()
                if (address != null) {
                    repository.resolvePlace(place.id, address.latitude, address.longitude)
                    resolved++
                }
                mapState.update { it.copy(resolvedCount = resolved) }
            }
            mapState.update { it.copy(resolving = false) }
        }
    }

    fun refreshCurrentLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return
        val client = LocationServices.getFusedLocationProviderClient(context)
        @Suppress("MissingPermission")
        fun accept(location: android.location.Location?) {
            if (location == null) return
            mapState.update { it.copy(currentLatitude = location.latitude, currentLongitude = location.longitude) }
            viewModelScope.launch(Dispatchers.IO) {
                val address = runCatching {
                    Geocoder(context, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
                }.getOrNull()
                val label = address?.subLocality
                    ?: address?.locality
                    ?: address?.thoroughfare
                    ?: address?.featureName?.takeUnless { it.matches(Regex("[A-Z0-9]{4}\\+[A-Z0-9]{3,}")) }
                    ?: address?.adminArea
                mapState.update { it.copy(currentLabel = label) }
            }
        }
        @Suppress("MissingPermission")
        client.lastLocation.addOnSuccessListener { cached ->
            if (cached != null) accept(cached)
            else client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener(::accept)
        }
    }

    fun savePlace(placeId: String?, name: String, address: String?) = viewModelScope.launch(Dispatchers.IO) {
        val detail = state.value.detail ?: return@launch
        val existing = placeId?.let { id -> detail.places.firstOrNull { it.id == id } }
        val cleanAddress = address?.trim()?.takeIf { it.isNotEmpty() }
        val query = listOfNotNull(name.trim(), cleanAddress, detail.trip.title, "India").distinct().joinToString(", ")
        val found = runCatching {
            Geocoder(context, Locale.getDefault()).getFromLocationName(query, 1)?.firstOrNull()
        }.getOrNull()
        repository.savePlace(
            (existing ?: TripPlaceEntity(
                id = UUID.randomUUID().toString(),
                tripId = tripId,
                name = name.trim(),
            )).copy(
                name = name.trim(),
                address = cleanAddress,
                latitude = found?.latitude ?: existing?.latitude,
                longitude = found?.longitude ?: existing?.longitude,
                verification = if (found != null) "resolved" else existing?.verification ?: "unresolved",
            ),
        )
    }

    fun removePlace(placeId: String) = viewModelScope.launch { repository.removePlace(placeId) }

    fun saveScheduledStop(
        activityId: String?,
        existingPlaceId: String?,
        name: String,
        address: String?,
        latitude: Double?,
        longitude: Double?,
        googlePlaceId: String?,
        dayId: String,
        startTime: String?,
        position: Int,
    ) = viewModelScope.launch(Dispatchers.IO) {
        val detail = state.value.detail ?: return@launch
        val existingActivity = activityId?.let { id -> detail.activities.firstOrNull { it.id == id } }
        // A place can be reused by more than one stop. Replacing one occurrence
        // must not silently move every other occurrence to the new location.
        val placeUseCount = detail.activities.count { it.placeId == existingPlaceId }
        val existingPlace = existingPlaceId?.takeIf { placeUseCount <= 1 }
            ?.let { id -> detail.places.firstOrNull { it.id == id } }
        val cleanName = name.trim()
        val cleanAddress = address?.trim()?.takeIf { it.isNotEmpty() }
        val resolved = if (latitude == null || longitude == null) runCatching {
            Geocoder(context, Locale.getDefault()).getFromLocationName(
                listOfNotNull(cleanName, cleanAddress, detail.trip.title).distinct().joinToString(", "),
                1,
            )?.firstOrNull()
        }.getOrNull() else null
        val place = (existingPlace ?: TripPlaceEntity(
            id = UUID.randomUUID().toString(),
            tripId = tripId,
            name = cleanName,
        )).copy(
            name = cleanName,
            address = cleanAddress,
            latitude = latitude ?: resolved?.latitude ?: existingPlace?.latitude,
            longitude = longitude ?: resolved?.longitude ?: existingPlace?.longitude,
            googlePlaceId = googlePlaceId ?: existingPlace?.googlePlaceId,
            verification = if (latitude != null || resolved != null) "resolved" else existingPlace?.verification ?: "unresolved",
        )
        val activity = (existingActivity ?: TripActivityEntity(
            id = UUID.randomUUID().toString(),
            tripId = tripId,
            dayId = dayId,
            title = cleanName,
            type = "visit",
            placeId = place.id,
            sortOrder = position,
        )).copy(
            dayId = dayId,
            title = cleanName,
            startTime = startTime?.trim()?.takeIf { it.isNotEmpty() },
            placeId = place.id,
        )
        repository.saveScheduledStop(place, activity, existingActivity?.dayId, position)
    }

    fun removeScheduledStop(activityId: String, placeId: String?) = viewModelScope.launch {
        repository.removeScheduledStop(activityId, placeId)
    }
}
