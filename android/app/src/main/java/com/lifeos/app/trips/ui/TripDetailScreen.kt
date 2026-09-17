package com.lifeos.app.trips.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import androidx.compose.runtime.MutableState
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.lifeos.app.BuildConfig
import com.lifeos.app.presentation.components.LifeOsCard
import com.lifeos.app.presentation.components.ScreenHeader
import com.lifeos.app.presentation.theme.LifeOsColors
import com.lifeos.app.trips.data.TripActivityEntity
import com.lifeos.app.trips.data.TripDayEntity
import com.lifeos.app.trips.data.TripDetail
import com.lifeos.app.trips.data.TripPlaceEntity
import com.lifeos.app.trips.location.TripLocationService
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

private object PlacePredictionCache {
    private const val MAX_ENTRIES = 40
    private val values = object : LinkedHashMap<String, List<AutocompletePrediction>>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<AutocompletePrediction>>?): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized fun get(query: String): List<AutocompletePrediction>? = values[query]
    @Synchronized fun put(query: String, predictions: List<AutocompletePrediction>) {
        values[query] = predictions
    }
}

private const val ALL_STOPS_CAMERA_KEY = "all-stops"

@Composable
fun TripDetailScreen(
    onMapFullscreenChanged: (Boolean) -> Unit = {},
    viewModel: TripDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail = state.detail
    var tab by remember { mutableIntStateOf(0) }
    var mapFullscreen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) startTracking(context, viewModel.tripId)
        else permissionMessage = "Location permission is needed to record this trip."
    }

    if (detail == null) {
        Column(Modifier.fillMaxSize().background(LifeOsColors.Background).padding(20.dp)) {
            Text("Loading trip…")
        }
    } else {
        BackHandler(enabled = mapFullscreen) {
            mapFullscreen = false
            onMapFullscreenChanged(false)
        }

        val zone = remember(detail.trip.timezone) {
            runCatching { ZoneId.of(detail.trip.timezone) }.getOrDefault(ZoneId.systemDefault())
        }
        val today = LocalDate.now(zone)
        val startDate = LocalDate.parse(detail.trip.startDate)
        val endDate = LocalDate.parse(detail.trip.endDate)
        val isDuringTrip = !today.isBefore(startDate) && !today.isAfter(endDate)
        val tabs = listOf(if (isDuringTrip) "Today" else "Plan", "Map", "Details", "Journal")
        val displayStatus = when {
            detail.trip.status == "active" -> "Tracking"
            today.isBefore(startDate) -> "Upcoming · ${java.time.temporal.ChronoUnit.DAYS.between(today, startDate)}d"
            today.isAfter(endDate) -> "Completed"
            detail.trip.status == "paused" -> "Paused"
            else -> "In progress"
        }

        // Hoist map state so it is created once per trip and never destroyed across tab switches or day filters
        val resolvedPlaces = remember(detail.places) {
            detail.places.filter { it.latitude != null && it.longitude != null }
        }
        val focus = remember(detail.trip.id) {
            resolvedPlaces.firstOrNull()?.let { LatLng(it.latitude!!, it.longitude!!) }
                ?: LatLng(28.6139, 77.2090)
        }
        val camera = rememberCameraPositionState(key = detail.trip.id) {
            position = CameraPosition.fromLatLngZoom(focus, 12f)
        }
        val mapReadyState = remember(detail.trip.id) { mutableStateOf(false) }
        val cameraByFilter = remember(detail.trip.id) { mutableMapOf<String, CameraPosition>() }

        Column(Modifier.fillMaxSize().background(LifeOsColors.Background)) {
            if (!mapFullscreen) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    ScreenHeader(detail.trip.title, formatTripRange(startDate, endDate))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (detail.trip.status == "active") {
                            AssistChip(onClick = {
                                context.startService(Intent(context, TripLocationService::class.java).setAction(TripLocationService.ACTION_STOP))
                                viewModel.setStatus("paused")
                            }, label = { Text("Pause tracking") }, leadingIcon = { Icon(Icons.Rounded.Pause, null, Modifier.size(18.dp)) })
                        } else {
                            AssistChip(onClick = {
                                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            }, label = { Text("Track trip") }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)) })
                        }
                        Surface(
                            modifier = Modifier.height(32.dp),
                            color = LifeOsColors.Primary.copy(alpha = 0.10f),
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                                Text(displayStatus, color = LifeOsColors.Primary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    permissionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                TabRow(selectedTabIndex = tab) {
                    tabs.forEachIndexed { index, title -> Tab(tab == index, { tab = index }, text = { Text(title) }) }
                }
            }

            Box(modifier = Modifier.fillMaxSize().then(if (!mapFullscreen) Modifier.weight(1f) else Modifier)) {
                if (!mapFullscreen) {
                    when (tab) {
                        0 -> PlanTab(detail, viewModel, today)
                        2 -> TripTab(detail, viewModel, state.backupMessage)
                        3 -> MemoriesTab(detail, viewModel)
                    }
                }
                // Keep ExploreTab alive across tab switches so the GoogleMap instance and cached tiles are retained
                val showMap = tab == 1 || mapFullscreen
                Box(
                    modifier = if (showMap) Modifier.fillMaxSize()
                               else Modifier.fillMaxSize().alpha(0f),
                ) {
                    if (showMap || mapReadyState.value) {
                        ExploreTab(
                            detail = detail,
                            locations = state.locations,
                            mapState = state.map,
                            viewModel = viewModel,
                            fullscreen = mapFullscreen,
                            onFullscreenChange = {
                                mapFullscreen = it
                                onMapFullscreenChanged(it)
                            },
                            camera = camera,
                            mapReadyState = mapReadyState,
                            cameraByFilter = cameraByFilter,
                            focus = focus,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanTab(detail: TripDetail, viewModel: TripDetailViewModel, today: LocalDate) {
    val days = detail.days.sortedBy { it.sortOrder }
    var selectedDayId by remember(detail.trip.id) {
        mutableStateOf(days.firstOrNull { it.date == today.toString() }?.id ?: days.firstOrNull()?.id)
    }
    val day = days.firstOrNull { it.id == selectedDayId } ?: days.firstOrNull()
    val activities = detail.activities.filter { it.dayId == day?.id }.sortedBy { it.sortOrder }
    val groups = remember(activities) { groupActivities(activities) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(10.dp)) }
        if (day == null) item { Text("No itinerary days yet.") }
        else {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(days, key = { _, item -> item.id }) { index, item ->
                        val date = LocalDate.parse(item.date)
                        FilterChip(
                            selected = item.id == day.id,
                            onClick = { selectedDayId = item.id },
                            label = { Text("${date.format(DateTimeFormatter.ofPattern("EEE d"))} · Day ${index + 1}") },
                        )
                    }
                }
            }
            item {
                Text(day.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
                Text(formatDayDate(day.date), color = LifeOsColors.OnSurfaceMuted)
            }
            items(groups, key = { it.root.id }) { group ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActivityCard(group.root, viewModel)
                    if (group.alternatives.isNotEmpty()) {
                        AlternativeChoiceCard(group.alternatives, viewModel)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ActivityCard(activity: TripActivityEntity, viewModel: TripDetailViewModel) {
    val isSkipped = activity.status == "skipped"
    LifeOsCard(
        modifier = Modifier.alpha(if (isSkipped) 0.62f else 1f),
        accent = if (activity.flexibility == "fixed") LifeOsColors.Primary else null,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(activity.startTime ?: "Flexible", color = LifeOsColors.Primary, fontWeight = FontWeight.Bold)
                Text(activity.type.replace('_', ' '), style = MaterialTheme.typography.labelSmall)
            }
            Text(
                activity.title,
                style = MaterialTheme.typography.titleMedium,
                textDecoration = if (isSkipped) TextDecoration.LineThrough else null,
            )
            activity.condition?.let { Text("When: $it", color = LifeOsColors.AccentAmber) }
            activity.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = activity.status == "completed",
                    onClick = { viewModel.setActivityStatus(activity.id, if (activity.status == "completed") "planned" else "completed") },
                    label = { Text(if (activity.status == "completed") "Completed" else "Done") },
                    leadingIcon = if (activity.status == "completed") {{ Icon(Icons.Rounded.CheckCircle, null) }} else null,
                )
                FilterChip(
                    selected = activity.status == "skipped",
                    onClick = { viewModel.setActivityStatus(activity.id, if (activity.status == "skipped") "planned" else "skipped") },
                    label = { Text(if (activity.status == "skipped") "Skipped" else "Skip") },
                )
            }
        }
    }
}

private data class ActivityGroup(
    val root: TripActivityEntity,
    val alternatives: List<TripActivityEntity>,
)

private fun groupActivities(activities: List<TripActivityEntity>): List<ActivityGroup> {
    val groups = mutableListOf<ActivityGroup>()
    activities.forEach { activity ->
        if (activity.type == "alternative" && groups.isNotEmpty()) {
            val last = groups.removeAt(groups.lastIndex)
            groups += last.copy(alternatives = last.alternatives + activity)
        } else {
            groups += ActivityGroup(activity, emptyList())
        }
    }
    return groups
}

@Composable
private fun AlternativeChoiceCard(alternatives: List<TripActivityEntity>, viewModel: TripDetailViewModel) {
    val selectedId = alternatives.firstOrNull { it.status == "completed" }?.id
    LifeOsCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Choose what applies", style = MaterialTheme.typography.titleSmall)
            Text("Pick one option when you know the arrival terminal.", style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
            alternatives.forEach { option ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        viewModel.chooseAlternative(option.id, alternatives.map { it.id })
                    }.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(
                        selected = selectedId == option.id,
                        onClick = { viewModel.chooseAlternative(option.id, alternatives.map { it.id }) },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(optionLabel(option), fontWeight = FontWeight.SemiBold)
                        Text(option.title, style = MaterialTheme.typography.bodyMedium)
                        option.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted) }
                    }
                }
            }
        }
    }
}

private fun optionLabel(option: TripActivityEntity): String {
    val terminal = Regex("(?i)\\bT[1-3]\\b").find(option.condition.orEmpty())?.value?.uppercase()
    return terminal ?: option.condition ?: "Option"
}

@Composable
private fun ExploreTab(
    detail: TripDetail,
    locations: List<com.lifeos.app.trips.data.TripLocationSampleEntity>,
    mapState: TripMapUiState,
    viewModel: TripDetailViewModel,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    camera: CameraPositionState,
    mapReadyState: MutableState<Boolean>,
    cameraByFilter: MutableMap<String, CameraPosition>,
    focus: LatLng,
) {
    val context = LocalContext.current
    var editingPlaces by remember { mutableStateOf(false) }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var showCurrentLocation by remember { mutableStateOf(false) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasLocationPermission = grants.values.any { it }
        if (hasLocationPermission) {
            showCurrentLocation = true
            viewModel.refreshCurrentLocation()
        }
    }
    LaunchedEffect(detail.trip.id) {
        viewModel.resolveItineraryPlaces()
        if (hasLocationPermission) viewModel.refreshCurrentLocation()
    }

    val days = detail.days.sortedBy { it.sortOrder }
    var selectedDayId by remember(detail.trip.id) { mutableStateOf<String?>(null) }
    val points = locations.map { LatLng(it.latitude, it.longitude) }
    val resolvedPlaces = remember(detail.places) {
        detail.places.filter { it.latitude != null && it.longitude != null }
    }
    val placeById = resolvedPlaces.associateBy { it.id }
    val allPlaceById = detail.places.associateBy { it.id }
    val itineraryPlaceIds = days.flatMap { day ->
        detail.activities.filter { it.dayId == day.id }.sortedBy { it.sortOrder }.mapNotNull { it.placeId }
    }.distinct()
    val allPlaceIds = (itineraryPlaceIds + detail.places.map { it.id }).distinct()
    val dayPlaceIds = selectedDayId?.let { dayId ->
        detail.activities.filter { it.dayId == dayId }.sortedBy { it.sortOrder }.mapNotNull { it.placeId }.distinct()
    }
    val displayedPlaceIds = dayPlaceIds ?: allPlaceIds
    val displayedPlaces = displayedPlaceIds.mapNotNull(placeById::get)
    val displayedListPlaces = displayedPlaceIds.mapNotNull(allPlaceById::get)
    val markerNumberById = displayedPlaceIds.withIndex().associate { (index, id) -> id to index + 1 }
    val routePoints = selectedDayId?.let { dayId ->
        detail.activities.asSequence()
            .filter { it.dayId == dayId && it.type != "alternative" }
            .sortedBy { it.sortOrder }
            .mapNotNull { it.placeId?.let(placeById::get) }
            .distinctBy { it.id }
            .map { LatLng(it.latitude!!, it.longitude!!) }
            .toList()
    }.orEmpty()

    var mapReady by mapReadyState
    var mapTimedOut by remember(detail.trip.id) { mutableStateOf(false) }
    val darkMap = isSystemInDarkTheme()
    var fitRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(BuildConfig.MAPS_CONFIGURED) {
        if (BuildConfig.MAPS_CONFIGURED) {
            delay(8_000)
            if (!mapReady) mapTimedOut = true
        }
    }
    // Each itinerary filter gets its own remembered camera. The first visit is
    // fitted to its stops; later visits restore the exact previous view with smooth animation.
    LaunchedEffect(mapReady, selectedDayId, fitRequest) {
        if (mapReady && !showCurrentLocation) {
            val filterKey = selectedDayId ?: ALL_STOPS_CAMERA_KEY
            val cached = cameraByFilter[filterKey]
            if (cached != null) {
                camera.animate(CameraUpdateFactory.newCameraPosition(cached))
            } else {
                val positions = displayedPlaces.map { LatLng(it.latitude!!, it.longitude!!) }
                when (positions.size) {
                    0 -> camera.animate(CameraUpdateFactory.newLatLngZoom(focus, 12f))
                    1 -> camera.animate(CameraUpdateFactory.newLatLngZoom(positions.first(), 14f))
                    else -> {
                        val bounds = LatLngBounds.builder().apply { positions.forEach(::include) }.build()
                        camera.animate(CameraUpdateFactory.newLatLngBounds(bounds, 90))
                    }
                }
                cameraByFilter[filterKey] = camera.position
            }
        }
    }
    val selectFilter: (String?) -> Unit = { nextDayId ->
        cameraByFilter[selectedDayId ?: ALL_STOPS_CAMERA_KEY] = camera.position
        selectedDayId = nextDayId
        showCurrentLocation = false
    }
    val refitCurrentFilter: () -> Unit = {
        showCurrentLocation = false
        cameraByFilter.remove(selectedDayId ?: ALL_STOPS_CAMERA_KEY)
        fitRequest++
    }
    LaunchedEffect(showCurrentLocation, mapState.currentLatitude, mapState.currentLongitude, mapReady) {
        val latitude = mapState.currentLatitude
        val longitude = mapState.currentLongitude
        if (showCurrentLocation && mapReady && latitude != null && longitude != null) {
            camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), 15f))
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
        if (BuildConfig.MAPS_CONFIGURED) {
            Box(
                if (fullscreen) Modifier.fillMaxSize()
                else Modifier.fillMaxWidth().height(260.dp),
            ) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = camera,
                    properties = MapProperties(isMyLocationEnabled = hasLocationPermission),
                    uiSettings = MapUiSettings(
                        zoomControlsEnabled = false,
                        myLocationButtonEnabled = false,
                        scrollGesturesEnabled = true,
                        zoomGesturesEnabled = true,
                    ),
                    mapColorScheme = if (darkMap) ComposeMapColorScheme.DARK else ComposeMapColorScheme.LIGHT,
                    onMapLoaded = { mapReady = true },
                ) {
                    if (routePoints.size >= 2) Polyline(points = routePoints, color = Color(0xFF175D9C), width = 8f)
                    if (detail.trip.status == "active" && points.size >= 2) {
                        Polyline(points = points, color = Color(0xFF18A999), width = 7f)
                    }
                    displayedPlaces.forEach { place ->
                        val position = LatLng(place.latitude!!, place.longitude!!)
                        val activity = detail.activities.firstOrNull { it.placeId == place.id }
                        val number = markerNumberById.getValue(place.id)
                        val markerState = remember(place.id, position) { MarkerState(position) }
                        val icon = remember(number, darkMap) { numberedMarkerIcon(context, number, darkMap) }
                        Marker(
                            state = markerState,
                            title = place.name,
                            snippet = activity?.let { listOfNotNull(it.startTime, it.title).joinToString(" · ") } ?: place.address,
                            icon = icon,
                            anchor = Offset(0.5f, 0.5f),
                            onInfoWindowClick = { openPlace(context, place) },
                        )
                    }
                    detail.visits.forEach { visit ->
                        Marker(remember(visit.id) { MarkerState(LatLng(visit.latitude, visit.longitude)) }, title = visit.name)
                    }
                }
                if (!mapReady) {
                    Surface(
                        modifier = Modifier.align(Alignment.Center).padding(20.dp),
                        shape = MaterialTheme.shapes.large,
                        color = LifeOsColors.Surface.copy(alpha = 0.96f),
                    ) {
                        if (mapTimedOut) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Map unavailable", style = MaterialTheme.typography.titleSmall)
                                Text("Open Google Maps to continue.", color = LifeOsColors.OnSurfaceMuted)
                                TextButton(onClick = { openTripArea(context, focus, detail.trip.title) }) { Text("Open in Maps") }
                            }
                        } else {
                            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text("Loading map…")
                            }
                        }
                    }
                }
                if (fullscreen) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(12.dp),
                        shape = MaterialTheme.shapes.large,
                        color = LifeOsColors.Surface.copy(alpha = 0.94f),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { onFullscreenChange(false) }) {
                                Icon(Icons.Rounded.FullscreenExit, contentDescription = "Exit full screen")
                            }
                            Text(detail.trip.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = refitCurrentFilter) {
                                Icon(Icons.Rounded.CenterFocusStrong, contentDescription = "Fit selected route")
                            }
                            IconButton(onClick = {
                                showCurrentLocation = true
                                viewModel.refreshCurrentLocation()
                            }) {
                                Icon(Icons.Rounded.LocationOn, contentDescription = "Show my location")
                            }
                        }
                    }
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp),
                        shape = MaterialTheme.shapes.large,
                        color = LifeOsColors.Surface.copy(alpha = 0.94f),
                    ) {
                        MapDaySelector(
                            days = days,
                            selectedDayId = selectedDayId,
                            currentLocationSelected = showCurrentLocation,
                            onSelect = selectFilter,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                } else {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                        shape = MaterialTheme.shapes.large,
                        color = LifeOsColors.Surface.copy(alpha = 0.92f),
                    ) {
                        Row {
                            IconButton(onClick = refitCurrentFilter) {
                                Icon(Icons.Rounded.CenterFocusStrong, contentDescription = "Fit selected route")
                            }
                            IconButton(onClick = { editingPlaces = true }) {
                                Icon(Icons.Rounded.Edit, contentDescription = "Edit trip places")
                            }
                            IconButton(onClick = { onFullscreenChange(true) }) {
                                Icon(Icons.Rounded.Fullscreen, contentDescription = "Open full screen map")
                            }
                        }
                    }
                }
            }
        } else {
            LifeOsCard(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Map, contentDescription = null, tint = LifeOsColors.Primary)
                    Text("See the route in Google Maps", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (resolvedPlaces.isEmpty()) {
                            "Your itinerary has ${detail.places.size} saved places. Open Maps to explore the trip area, or search for a place below."
                        } else {
                            "${resolvedPlaces.size} saved places are ready. Open Maps for the live map and directions."
                        },
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                    OutlinedButton(onClick = { openTripArea(context, focus, detail.trip.title) }) {
                        Icon(Icons.Rounded.Directions, null)
                        Text(" Open in Maps")
                    }
                }
            }
        }
        if (!fullscreen) LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            item {
                MapDaySelector(
                    days = days,
                    selectedDayId = selectedDayId,
                    currentLocationSelected = showCurrentLocation,
                    onSelect = selectFilter,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        AssistChip(
                            onClick = {
                                if (hasLocationPermission) {
                                    showCurrentLocation = true
                                    viewModel.refreshCurrentLocation()
                                } else {
                                    locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                }
                            },
                            label = {
                                Text(
                                    when {
                                        mapState.currentLabel != null -> "Near ${mapState.currentLabel}"
                                        hasLocationPermission -> "My location"
                                        else -> "Enable my location"
                                    },
                                )
                            },
                            leadingIcon = { Icon(Icons.Rounded.LocationOn, null, Modifier.size(18.dp)) },
                        )
                    }
                    val nearbyCenter = if (showCurrentLocation && mapState.currentLatitude != null && mapState.currentLongitude != null) {
                        LatLng(mapState.currentLatitude, mapState.currentLongitude)
                    } else focus
                    item { AssistChip(onClick = { openNearby(context, nearbyCenter, "family restaurants") }, label = { Text("Food") }) }
                    item { AssistChip(onClick = { openNearby(context, nearbyCenter, "pharmacy") }, label = { Text("Pharmacy") }) }
                    item { AssistChip(onClick = { openNearby(context, nearbyCenter, "public toilets") }, label = { Text("Toilets") }) }
                    item {
                        AssistChip(
                            onClick = { editingPlaces = true },
                            label = { Text("Edit places") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            if (mapState.resolving) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Finding itinerary locations… ${mapState.resolvedCount}/${mapState.totalCount}", color = LifeOsColors.OnSurfaceMuted)
                }
            }
            item { Text("Places", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(displayedListPlaces, key = { it.id }) { place ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (place.latitude != null) openPlace(context, place)
                        else searchPlace(context, place)
                    }.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${markerNumberById.getValue(place.id)}. ${place.name}")
                        Text(
                            if (place.latitude != null && place.longitude != null) "Tap to view in Google Maps" else "Tap to search in Google Maps",
                            style = MaterialTheme.typography.bodySmall,
                            color = LifeOsColors.OnSurfaceMuted,
                        )
                    }
                    Icon(Icons.Rounded.LocationOn, contentDescription = "View place in Google Maps")
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        } // closes Column
        if (editingPlaces && !fullscreen) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = LifeOsColors.Background,
            ) {
                PlaceEditor(detail, viewModel, onDone = { editingPlaces = false })
            }
        }
    } // closes Box
} // closes ExploreTab

@Composable
private fun MapDaySelector(
    days: List<TripDayEntity>,
    selectedDayId: String?,
    currentLocationSelected: Boolean,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = selectedDayId == null && !currentLocationSelected,
                onClick = { onSelect(null) },
                label = { Text("All stops") },
            )
        }
        items(days, key = { it.id }) { day ->
            val date = LocalDate.parse(day.date)
            FilterChip(
                selected = selectedDayId == day.id && !currentLocationSelected,
                onClick = { onSelect(day.id) },
                label = { Text(date.format(DateTimeFormatter.ofPattern("EEE d"))) },
            )
        }
    }
}

@Composable
private fun PlaceEditor(detail: TripDetail, viewModel: TripDetailViewModel, onDone: () -> Unit) {
    var editing by remember { mutableStateOf<TripActivityEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<TripActivityEntity?>(null) }
    val days = detail.days.sortedBy { it.sortOrder }
    val dayById = days.associateBy { it.id }
    val placeById = detail.places.associateBy { it.id }
    val scheduledStops = days.flatMap { day ->
        detail.activities.filter { it.dayId == day.id && it.placeId != null && it.type != "alternative" }
            .sortedBy { it.sortOrder }
    }

    Column(Modifier.fillMaxSize().background(LifeOsColors.Background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Edit map itinerary", style = MaterialTheme.typography.titleLarge)
                Text("Every pin belongs to a day, time, and route position.", color = LifeOsColors.OnSurfaceMuted)
            }
            TextButton(onClick = onDone) { Text("Done") }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.AddLocationAlt, null)
                    Text(" Add itinerary stop")
                }
            }
            items(scheduledStops, key = { it.id }) { activity ->
                val place = placeById[activity.placeId] ?: return@items
                val day = dayById[activity.dayId]
                val route = detail.activities.filter { it.dayId == activity.dayId && it.placeId != null && it.type != "alternative" }
                    .sortedBy { it.sortOrder }
                val stopNumber = route.indexOfFirst { it.id == activity.id } + 1
                LifeOsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(34.dp),
                            shape = MaterialTheme.shapes.large,
                            color = LifeOsColors.Primary.copy(alpha = 0.12f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(stopNumber.toString(), color = LifeOsColors.Primary, fontWeight = FontWeight.Bold)
                            }
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(place.name, style = MaterialTheme.typography.titleMedium)
                            val dateText = day?.let { LocalDate.parse(it.date).format(DateTimeFormatter.ofPattern("EEE, MMM d")) }
                            Text(
                                listOfNotNull(dateText, activity.startTime ?: "Flexible time", "Stop $stopNumber").joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = LifeOsColors.Primary,
                            )
                            place.address?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
                            }
                        }
                        IconButton(onClick = { editing = activity }) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Edit ${place.name}")
                        }
                        IconButton(onClick = { deleting = activity }) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = "Remove ${place.name}",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (adding || editing != null) {
        StopEditDialog(
            detail = detail,
            activity = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { name, address, latitude, longitude, googlePlaceId, dayId, time, position ->
                viewModel.saveScheduledStop(
                    activityId = editing?.id,
                    existingPlaceId = editing?.placeId,
                    name = name,
                    address = address,
                    latitude = latitude,
                    longitude = longitude,
                    googlePlaceId = googlePlaceId,
                    dayId = dayId,
                    startTime = time,
                    position = position,
                )
                adding = false
                editing = null
            },
        )
    }
    deleting?.let { activity ->
        val place = placeById[activity.placeId]
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove ${place?.name ?: activity.title}?") },
            text = { Text("This stop will be removed from the day plan and the route will be renumbered.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.removeScheduledStop(activity.id, activity.placeId)
                    deleting = null
                }) { Text("Remove stop") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private data class SelectedMapPlace(
    val name: String,
    val address: String?,
    val latitude: Double,
    val longitude: Double,
    val googlePlaceId: String,
)

@Composable
private fun StopEditDialog(
    detail: TripDetail,
    activity: TripActivityEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String?, Double?, Double?, String?, String, String?, Int) -> Unit,
) {
    val days = detail.days.sortedBy { it.sortOrder }
    val existingPlace = activity?.placeId?.let { id -> detail.places.firstOrNull { it.id == id } }
    var name by remember(activity?.id) { mutableStateOf(existingPlace?.name.orEmpty()) }
    var address by remember(activity?.id) { mutableStateOf(existingPlace?.address.orEmpty()) }
    var selectedMapPlace by remember(activity?.id) { mutableStateOf<SelectedMapPlace?>(null) }
    var dayId by remember(activity?.id) { mutableStateOf(activity?.dayId ?: days.firstOrNull()?.id.orEmpty()) }
    var time by remember(activity?.id) { mutableStateOf(activity?.startTime.orEmpty()) }
    val stopsForDay = detail.activities.filter {
        it.dayId == dayId && it.placeId != null && it.type != "alternative" && it.id != activity?.id
    }.sortedBy { it.sortOrder }
    val originalPosition = activity?.takeIf { it.dayId == dayId }?.let { current ->
        detail.activities.filter { it.dayId == dayId && it.placeId != null && it.type != "alternative" }
            .sortedBy { it.sortOrder }.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
    } ?: stopsForDay.size
    var position by remember(activity?.id, dayId) { mutableIntStateOf(originalPosition.coerceIn(0, stopsForDay.size)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (activity == null) "Add itinerary stop" else "Edit itinerary stop") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "Search and choose a verified map result, then place it in your plan.",
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                }
                item {
                    PlaceAutocompleteField(
                        query = name,
                        onQueryChange = { name = it; selectedMapPlace = null },
                        onSelected = { selected ->
                            selectedMapPlace = selected
                            name = selected.name
                            address = selected.address.orEmpty()
                        },
                    )
                }
                item {
                OutlinedTextField(
                    address,
                    { address = it; selectedMapPlace = null },
                    label = { Text("Selected address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                }
                item { Text("Day", style = MaterialTheme.typography.labelLarge) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(days, key = { it.id }) { day ->
                            FilterChip(
                                selected = dayId == day.id,
                                onClick = { dayId = day.id },
                                label = { Text(LocalDate.parse(day.date).format(DateTimeFormatter.ofPattern("EEE, MMM d"))) },
                            )
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = time,
                        onValueChange = { time = it },
                        label = { Text("Time") },
                        placeholder = { Text("09:00 or flexible") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Schedule, null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item { Text("Route position", style = MaterialTheme.typography.labelLarge) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(stopsForDay.size + 1) { index ->
                            val label = when {
                                index == 0 -> "1 · First"
                                else -> "${index + 1} · after ${stopsForDay[index - 1].title}"
                            }
                            FilterChip(
                                selected = position == index,
                                onClick = { position = index },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val selected = selectedMapPlace
                    val unchanged = selected == null &&
                        name.trim() == existingPlace?.name &&
                        address.trim().ifEmpty { null } == existingPlace.address
                    onSave(
                        name.trim(),
                        address.trim().takeIf { it.isNotEmpty() },
                        selected?.latitude ?: existingPlace?.latitude?.takeIf { unchanged },
                        selected?.longitude ?: existingPlace?.longitude?.takeIf { unchanged },
                        selected?.googlePlaceId ?: existingPlace?.googlePlaceId?.takeIf { unchanged },
                        dayId,
                        time.trim().takeIf { it.isNotEmpty() },
                        position,
                    )
                },
                enabled = name.isNotBlank() && dayId.isNotBlank(),
            ) {
                Text(if (activity == null) "Add to plan" else "Save changes")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PlaceAutocompleteField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSelected: (SelectedMapPlace) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var predictions by remember { mutableStateOf<List<AutocompletePrediction>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchMessage by remember { mutableStateOf<String?>(null) }
    val apiKey = remember {
        runCatching {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData?.getString("com.google.android.geo.API_KEY").orEmpty()
        }.getOrDefault("")
    }
    val placesClient = remember(apiKey) {
        if (apiKey.isBlank()) null else runCatching {
            if (!Places.isInitialized()) Places.initializeWithNewPlacesApiEnabled(context.applicationContext, apiKey)
            Places.createClient(context.applicationContext)
        }.getOrNull()
    }
    val token = remember { AutocompleteSessionToken.newInstance() }

    LaunchedEffect(query, placesClient) {
        predictions = emptyList()
        searchMessage = null
        if (query.trim().length < 2 || placesClient == null) return@LaunchedEffect
        delay(300)
        val cacheKey = query.trim().lowercase(Locale.ROOT)
        PlacePredictionCache.get(cacheKey)?.let { cached ->
            predictions = cached
            searching = false
            return@LaunchedEffect
        }
        searching = true
        val request = FindAutocompletePredictionsRequest.builder()
            .setSessionToken(token)
            .setQuery(query.trim())
            .build()
        placesClient.findAutocompletePredictions(request)
            .addOnSuccessListener { response ->
                predictions = response.autocompletePredictions.take(5)
                PlacePredictionCache.put(cacheKey, predictions)
                searching = false
                if (predictions.isEmpty()) searchMessage = "No matching places found"
            }
            .addOnFailureListener {
                searching = false
                searchMessage = "Place suggestions unavailable. You can still enter the address."
            }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Search Google Maps") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        predictions.forEach { prediction ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable {
                    val request = FetchPlaceRequest.builder(
                        prediction.placeId,
                        listOf(Place.Field.ID, Place.Field.DISPLAY_NAME, Place.Field.FORMATTED_ADDRESS, Place.Field.LOCATION),
                    ).setSessionToken(token).build()
                    searching = true
                    placesClient?.fetchPlace(request)
                        ?.addOnSuccessListener { response ->
                            val place = response.place
                            val location = place.location
                            if (location != null && place.id != null) {
                                onSelected(
                                    SelectedMapPlace(
                                        name = place.displayName ?: prediction.getPrimaryText(null).toString(),
                                        address = place.formattedAddress,
                                        latitude = location.latitude,
                                        longitude = location.longitude,
                                        googlePlaceId = place.id!!,
                                    ),
                                )
                                focusManager.clearFocus()
                                keyboard?.hide()
                                predictions = emptyList()
                                searchMessage = "Verified place selected"
                            }
                            searching = false
                        }
                        ?.addOnFailureListener {
                            searching = false
                            searchMessage = "Could not load that place. Try another result."
                        }
                },
                shape = MaterialTheme.shapes.medium,
                color = LifeOsColors.Surface,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Text(prediction.getPrimaryText(null).toString(), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        prediction.getSecondaryText(null).toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                }
            }
        }
        searchMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
        }
    }
}

@Composable
private fun TripTab(detail: TripDetail, viewModel: TripDetailViewModel, backupMessage: String?) {
    var expenseDialog by remember { mutableStateOf(false) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.addAttachment(it.toString(), kind = "document") }
    }
    val total = detail.expenses.mapNotNull { runCatching { BigDecimal(it.amount) }.getOrNull() }
        .fold(BigDecimal.ZERO, BigDecimal::add)
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(10.dp)) }
        item {
            LifeOsCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Trip overview", style = MaterialTheme.typography.titleMedium)
                    val plannedStops = detail.activities.count { it.type != "alternative" }
                    Text("${detail.days.size} days · $plannedStops planned stops · ${detail.places.size} saved places")
                    val unresolved = detail.places.count { it.latitude == null || it.longitude == null }
                    if (unresolved > 0) Text("$unresolved places need a map pin", color = LifeOsColors.AccentAmber)
                    OutlinedButton(onClick = viewModel::backup) {
                        Icon(Icons.Rounded.CloudUpload, null)
                        Text(" Back up trip")
                    }
                    backupMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted) }
                }
            }
        }
        item {
            LifeOsCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bookings and documents", style = MaterialTheme.typography.titleMedium)
                    Text("Keep tickets, confirmations, and PDFs available offline.", color = LifeOsColors.OnSurfaceMuted)
                    OutlinedButton(onClick = { documentPicker.launch(arrayOf("application/pdf", "image/*", "text/plain")) }) {
                        Text("Add document")
                    }
                    detail.attachments.filter { it.kind == "document" }.forEach { Text("• ${it.displayName}") }
                }
            }
        }
        item {
            LifeOsCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Trip spending", style = MaterialTheme.typography.titleMedium)
                        Text("${detail.trip.currency} $total", style = MaterialTheme.typography.titleLarge)
                    }
                    AssistChip(onClick = { expenseDialog = true }, label = { Text("Add") })
                }
            }
        }
        items(detail.expenses, key = { it.id }) { expense ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text(expense.title); Text(expense.category, style = MaterialTheme.typography.bodySmall) }
                Text("${expense.currency} ${expense.amount}")
            }
        }
        if (detail.reminders.isNotEmpty()) {
            item { Text("Reminders", style = MaterialTheme.typography.titleMedium) }
            items(detail.reminders, key = { it.id }) { reminder ->
                LifeOsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Event, null, tint = LifeOsColors.Primary)
                            Text(reminder.title, style = MaterialTheme.typography.titleSmall)
                        }
                        reminder.note?.let { Text(it, color = LifeOsColors.OnSurfaceMuted) }
                        when {
                            reminder.enabled -> Text("Reminder enabled", color = LifeOsColors.Positive)
                            reminder.localDateTime != null -> TextButton(onClick = { viewModel.enableReminder(reminder.id) }) { Text("Remind me") }
                            else -> Text("No time was included", style = MaterialTheme.typography.bodySmall, color = LifeOsColors.OnSurfaceMuted)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (expenseDialog) ExpenseDialog(detail.trip.currency, { expenseDialog = false }) { title, amount, category, date ->
        viewModel.addExpense(title, amount, category, date)
        expenseDialog = false
    }
}

@Composable
private fun MemoriesTab(detail: TripDetail, viewModel: TripDetailViewModel) {
    val context = LocalContext.current
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.addAttachment(it.toString()) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(10.dp)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    label = { Text("Add photo") },
                    leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(18.dp)) },
                )
                AssistChip(onClick = { shareRecap(context, detail) }, label = { Text("Share recap") })
            }
        }
        if (detail.visits.isEmpty()) item {
            LifeOsCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.LocationOn, null, tint = LifeOsColors.Primary)
                    Text("Your travel journal starts here", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Places you visit will appear here while trip tracking is on. You can add photos at any time.",
                        color = LifeOsColors.OnSurfaceMuted,
                    )
                }
            }
        }
        items(detail.visits, key = { it.id }) { visit ->
            LifeOsCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.LocationOn, null, tint = LifeOsColors.Primary)
                    Column {
                        Text(visit.name, style = MaterialTheme.typography.titleMedium)
                        Text(formatInstant(visit.arrivedAt, detail.trip.timezone), color = LifeOsColors.OnSurfaceMuted)
                        Text(if (visit.confirmed) "Confirmed visit" else "Needs confirmation", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (detail.attachments.isNotEmpty()) {
            item { Text("Photos and documents", style = MaterialTheme.typography.titleMedium) }
            items(detail.attachments, key = { it.id }) { attachment -> Text("• ${attachment.displayName}") }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ExpenseDialog(currency: String, onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Food") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add trip expense") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Description") })
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount ($currency)") })
                OutlinedTextField(category, { category = it }, label = { Text("Category") })
                OutlinedTextField(date, { date = it }, label = { Text("Date (YYYY-MM-DD)") })
            }
        },
        confirmButton = {
            Button(onClick = { onSave(title, amount, category, date) }, enabled = title.isNotBlank() && amount.toBigDecimalOrNull() != null) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun startTracking(context: Context, tripId: String) {
    val intent = Intent(context, TripLocationService::class.java).putExtra(TripLocationService.EXTRA_TRIP_ID, tripId)
    ContextCompat.startForegroundService(context, intent)
}

private fun numberedMarkerIcon(context: Context, number: Int, dark: Boolean): BitmapDescriptor {
    val density = context.resources.displayMetrics.density
    val size = (18f * density).toInt().coerceAtLeast(42)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val center = size / 2f
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) AndroidColor.rgb(226, 239, 255) else AndroidColor.WHITE
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) AndroidColor.rgb(80, 157, 224) else AndroidColor.rgb(21, 93, 156)
    }
    canvas.drawCircle(center, center, center - 1f, border)
    canvas.drawCircle(center, center, center - (1.5f * density), fill)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = (if (number < 10) 9f else 7.5f) * density
    }
    val baseline = center - (text.ascent() + text.descent()) / 2f
    canvas.drawText(number.toString(), center, baseline, text)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun openPlace(context: Context, place: TripPlaceEntity) {
    val latitude = place.latitude ?: return
    val longitude = place.longitude ?: return
    val query = Uri.encode("$latitude,$longitude")
    val placeId = place.googlePlaceId?.let { "&query_place_id=${Uri.encode(it)}" }.orEmpty()
    val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$query$placeId")
    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun openNearby(context: Context, center: LatLng, query: String) {
    val uri = Uri.parse("geo:${center.latitude},${center.longitude}?q=${Uri.encode(query)}")
    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun searchPlace(context: Context, place: TripPlaceEntity) {
    val query = listOfNotNull(place.name, place.address).joinToString(", ")
    val uri = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun openTripArea(context: Context, center: LatLng, title: String) {
    val uri = Uri.parse("geo:${center.latitude},${center.longitude}?q=${Uri.encode(title)}")
    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun formatTripRange(start: LocalDate, end: LocalDate): String {
    val startText = start.format(DateTimeFormatter.ofPattern("MMM d"))
    val endText = if (start.year == end.year && start.month == end.month) {
        end.format(DateTimeFormatter.ofPattern("d, yyyy"))
    } else if (start.year == end.year) {
        end.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
    } else {
        end.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
    }
    val days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1
    return "$startText–$endText · $days days"
}

private fun formatDayDate(raw: String): String =
    LocalDate.parse(raw).format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))

private fun formatInstant(epoch: Long, timezone: String): String {
    val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())
    return DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a").withZone(zone).format(Instant.ofEpochMilli(epoch))
}

private fun shareRecap(context: Context, detail: TripDetail) {
    val recap = buildString {
        appendLine(detail.trip.title)
        appendLine("${detail.trip.startDate} to ${detail.trip.endDate}")
        appendLine()
        if (detail.visits.isNotEmpty()) {
            appendLine("Places visited")
            detail.visits.sortedBy { it.arrivedAt }.forEach { visit ->
                appendLine("• ${visit.name} · ${formatInstant(visit.arrivedAt, detail.trip.timezone)}")
            }
        }
        if (detail.expenses.isNotEmpty()) {
            val total = detail.expenses.mapNotNull { it.amount.toBigDecimalOrNull() }.fold(BigDecimal.ZERO, BigDecimal::add)
            appendLine()
            appendLine("Recorded trip spending: ${detail.trip.currency} $total")
        }
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, detail.trip.title)
        putExtra(Intent.EXTRA_TEXT, recap)
    }
    context.startActivity(Intent.createChooser(intent, "Share trip recap"))
}
