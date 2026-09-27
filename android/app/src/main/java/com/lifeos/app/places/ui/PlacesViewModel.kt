package com.lifeos.app.places.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.repository.SettingsRepository
import com.lifeos.app.places.data.PlaceStopEntity
import com.lifeos.app.places.data.PlacesRepository
import com.lifeos.app.places.data.SavedPlaceEntity
import com.lifeos.app.places.data.SavedPlaceKind
import com.lifeos.app.places.domain.DayMovement
import com.lifeos.app.places.domain.DaySummary
import com.lifeos.app.places.domain.HomeInference
import com.lifeos.app.places.domain.PlacesTimeline
import com.lifeos.app.places.domain.TimelineEntry
import com.lifeos.app.trips.data.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class PlacesUiState(
    val day: LocalDate = LocalDate.now(),
    val enabled: Boolean = true,
    val loading: Boolean = true,
    val home: SavedPlaceEntity? = null,
    val homeSuggestion: HomeInference.Suggestion? = null,
    val movement: DayMovement? = null,
    val entries: List<TimelineEntry> = emptyList(),
    /** Set when this day was part of a recorded trip: its travel was planned. */
    val tripTitle: String? = null,
) {
    val isToday: Boolean get() = day == LocalDate.now()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlacesViewModel @Inject constructor(
    private val repository: PlacesRepository,
    private val tripRepository: TripRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val day = MutableStateFlow(LocalDate.now())

    private val dayView = day.flatMapLatest { date ->
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        combine(
            repository.observeStopsBetween(start, end),
            repository.observeSamplesBetween(start, end),
            tripRepository.observeStaysBetween(start, end),
            repository.observeSavedPlaces(),
        ) { stops, samples, tripStays, saved ->
            val home = saved.firstOrNull { it.kind == SavedPlaceKind.HOME }
            val now = System.currentTimeMillis()
            DayView(
                date = date,
                home = home,
                saved = saved,
                movement = DaySummary.of(samples, home?.let { it.latitude to it.longitude }, zone),
                entries = PlacesTimeline.build(stops, samples, tripStays, start, end, now),
                tripTitle = tripStays.firstOrNull()?.tripTitle,
            )
        }
    }

    /** The last week's stops, for the Home suggestion. */
    private val recentStops = repository.observeStopsBetween(
        LocalDate.now().minusDays(HomeInference.NIGHTS_LOOKED_AT.toLong()).atStartOfDay(zone).toInstant().toEpochMilli(),
        Long.MAX_VALUE,
    )

    val state: StateFlow<PlacesUiState> = combine(
        dayView,
        recentStops,
        settingsRepository.placesEnabled,
    ) { view, recent, enabled ->
        val suggestion = if (view.home != null) null else HomeInference.suggest(
            stops = recent,
            excluded = view.saved.map { it.latitude to it.longitude },
            today = LocalDate.now(),
            zone = zone,
            now = System.currentTimeMillis(),
        )
        PlacesUiState(
            day = view.date,
            enabled = enabled,
            loading = false,
            home = view.home,
            homeSuggestion = suggestion,
            movement = view.movement,
            entries = view.entries,
            tripTitle = view.tripTitle,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlacesUiState())

    fun previousDay() = day.update { it.minusDays(1) }
    fun nextDay() = day.update { if (it.isBefore(LocalDate.now())) it.plusDays(1) else it }

    fun rename(stop: PlaceStopEntity, name: String) = viewModelScope.launch { repository.renameStop(stop.id, name) }
    fun remove(stop: PlaceStopEntity) = viewModelScope.launch { repository.removeStop(stop.id) }
    fun saveAs(stop: PlaceStopEntity, kind: String) =
        viewModelScope.launch { repository.savePlace(kind, stop.latitude, stop.longitude) }

    fun confirmHome(suggestion: HomeInference.Suggestion) =
        viewModelScope.launch { repository.savePlace(SavedPlaceKind.HOME, suggestion.latitude, suggestion.longitude) }

    fun dismissHome(suggestion: HomeInference.Suggestion) =
        viewModelScope.launch { repository.dismissHomeSuggestion(suggestion.latitude, suggestion.longitude) }

    private data class DayView(
        val date: LocalDate,
        val home: SavedPlaceEntity?,
        val saved: List<SavedPlaceEntity>,
        val movement: DayMovement,
        val entries: List<TimelineEntry>,
        val tripTitle: String?,
    )
}
