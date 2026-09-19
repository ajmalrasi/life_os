package com.lifeos.app.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lifeos.app.presentation.chat.ChatScreen
import com.lifeos.app.presentation.dashboard.DashboardScreen
import com.lifeos.app.presentation.insights.InsightsScreen
import com.lifeos.app.presentation.money.CurrentCycleDetailsScreen
import com.lifeos.app.presentation.money.MoneyScreen
import com.lifeos.app.presentation.money.StatementDetailsScreen
import com.lifeos.app.presentation.mood.MoodScreen
import com.lifeos.app.presentation.priority.PriorityDetailsScreen
import com.lifeos.app.presentation.priority.PriorityScreen
import com.lifeos.app.presentation.settings.SettingsScreen
import com.lifeos.app.presentation.theme.LifeOsColors
import com.lifeos.app.trips.ui.TripDetailScreen
import com.lifeos.app.trips.ui.TripsScreen
import kotlinx.coroutines.launch

enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    DASHBOARD("dashboard", "Today", Icons.Rounded.WbSunny),
    MOOD("mood", "Pulse", Icons.Rounded.Favorite),
    MONEY("money", "Money", Icons.Rounded.AccountBalanceWallet),
    PRIORITY("priority", "Priority", Icons.Rounded.Mail),
    INSIGHTS("insights", "Insights", Icons.Rounded.Insights),
    CHAT("chat", "Ask", Icons.Rounded.AutoAwesome),
    TRIPS("trips", "Trips", Icons.Rounded.TravelExplore),
    SETTINGS("settings", "Settings", Icons.Rounded.Tune),
}

internal val navigationDestinations = listOf(
    Destination.MONEY,
    Destination.PRIORITY,
    Destination.DASHBOARD,
    Destination.MOOD,
    Destination.INSIGHTS,
    Destination.CHAT,
    Destination.TRIPS,
    Destination.SETTINGS,
)
private const val STATEMENT_DETAILS_ROUTE = "statement/{statementId}"
private const val CURRENT_CYCLE_DETAILS_ROUTE = "cycle/{bank}/{cardLast4}"
private const val PRIORITY_DETAILS_ROUTE = "priority/{itemId}"
private const val TRIP_DETAILS_ROUTE = "trip/{tripId}"
internal const val DEFAULT_DESTINATION_ROUTE = "money"

@Composable
fun LifeOsApp(
    startTab: String? = null,
    openRequestId: Long = 0L,
    openPriorityItemId: Int? = null,
    startDestination: String = DEFAULT_DESTINATION_ROUTE,
    onDestinationChanged: ((Destination) -> Unit)? = null,
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val previousRoute = currentBackStackEntry?.let {
        navController.previousBackStackEntry?.destination?.route
    }
    var moneyOverviewRequest by remember { mutableStateOf(0L) }
    var tripMapFullscreen by remember { mutableStateOf(false) }

    LaunchedEffect(currentRoute) {
        if (currentRoute != TRIP_DETAILS_ROUTE) tripMapFullscreen = false
        topLevelDestinationFor(currentRoute)?.let { dest ->
            onDestinationChanged?.invoke(dest)
        }
    }

    val navigateTo: (Destination) -> Unit = { destination ->
        navController.navigate(destination.route) {
            // Keep the page we came from so both visible and system Back retrace
            // the user's journey, including Today -> Settings -> Today.
            launchSingleTop = true
        }
        scope.launch { drawerState.close() }
    }

    // Deep links still reach the quieter LifeOS areas, but Money remains under
    // them on the back stack and is the app's normal home.
    LaunchedEffect(startTab, openRequestId) {
        val destination = Destination.entries.firstOrNull { it.route == startTab }
        if (destination == Destination.MONEY) {
            navController.navigate(Destination.MONEY.route) {
                popUpTo(navController.graph.findStartDestination().id)
                launchSingleTop = true
            }
            moneyOverviewRequest = openRequestId
        } else if (destination != null) {
            navController.navigate(destination.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            if (destination == Destination.PRIORITY && openPriorityItemId != null) {
                navController.navigate("priority/$openPriorityItemId")
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Let the map own every drag while it is full screen. The drawer can
        // still be opened again after leaving the map.
        gesturesEnabled = !tripMapFullscreen,
        drawerContent = {
            LifeOsNavigationDrawer(
                currentRoute = currentRoute,
                onDestinationSelected = navigateTo,
            )
        },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = LifeOsColors.Background,
            topBar = {
                if (!tripMapFullscreen) {
                    if (previousRoute != null) {
                        LifeOsBackBar(
                            previousLabel = Destination.entries.firstOrNull { it.route == previousRoute }?.label,
                            onBack = { navController.popBackStack() },
                            onOpenNavigation = { scope.launch { drawerState.open() } },
                        )
                    } else if (currentRoute != Destination.MONEY.route) {
                        LifeOsTopMenuBar(
                            onOpenNavigation = { scope.launch { drawerState.open() } },
                        )
                    }
                }
            },
        ) { contentPadding ->
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.padding(contentPadding),
            ) {
                composable(Destination.MONEY.route) {
                    MoneyScreen(
                        overviewRequestId = moneyOverviewRequest,
                        showNavigationButton = previousRoute == null,
                        navigationDrawerOpen = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open,
                        onOpenNavigation = { scope.launch { drawerState.open() } },
                        onOpenStatement = { id -> navController.navigate("statement/$id") },
                        onOpenCurrentCycle = { bank, cardLast4 ->
                            navController.navigate("cycle/$bank/$cardLast4")
                        },
                    )
                }
                composable(Destination.DASHBOARD.route) {
                    DashboardScreen(
                        onOpenSettings = { navigateTo(Destination.SETTINGS) },
                        onOpenTrips = { navigateTo(Destination.TRIPS) },
                    )
                }
                composable(Destination.MOOD.route) { MoodScreen() }
                composable(Destination.PRIORITY.route) {
                    PriorityScreen(onOpenItem = { id -> navController.navigate("priority/$id") })
                }
                composable(Destination.INSIGHTS.route) { InsightsScreen() }
                composable(Destination.CHAT.route) { ChatScreen() }
                composable(Destination.TRIPS.route) {
                    TripsScreen(onOpenTrip = { id -> navController.navigate("trip/$id") })
                }
                composable(Destination.SETTINGS.route) {
                    SettingsScreen()
                }
                composable(
                    route = STATEMENT_DETAILS_ROUTE,
                    arguments = listOf(navArgument("statementId") { type = NavType.IntType }),
                ) {
                    StatementDetailsScreen(onBack = { navController.popBackStack() })
                }
                composable(
                    route = CURRENT_CYCLE_DETAILS_ROUTE,
                    arguments = listOf(
                        navArgument("bank") { type = NavType.StringType },
                        navArgument("cardLast4") { type = NavType.StringType },
                    ),
                ) {
                    CurrentCycleDetailsScreen(onBack = { navController.popBackStack() })
                }
                composable(
                    route = PRIORITY_DETAILS_ROUTE,
                    arguments = listOf(navArgument("itemId") { type = NavType.IntType }),
                ) {
                    PriorityDetailsScreen()
                }
                composable(
                    route = TRIP_DETAILS_ROUTE,
                    arguments = listOf(navArgument("tripId") { type = NavType.StringType }),
                ) {
                    TripDetailScreen(
                        onMapFullscreenChanged = { tripMapFullscreen = it },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
    // Register after the page handlers: Back dismisses an open/opening drawer
    // before it can change a Money section or pop a destination.
    BackHandler(enabled = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open) {
        scope.launch { drawerState.close() }
    }
}

/** Outside the scrolling page, so Back is available even in long detail views. */
@Composable
private fun LifeOsBackBar(previousLabel: String?, onBack: () -> Unit, onOpenNavigation: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
            Text(
                text = previousLabel?.let { "Back to $it" } ?: "Back",
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onOpenNavigation) {
            Icon(Icons.Rounded.Menu, contentDescription = "Open navigation drawer")
        }
    }
}

/** Shown when a non-Money area is the root screen, giving quick access to the drawer. */
@Composable
private fun LifeOsTopMenuBar(onOpenNavigation: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenNavigation) {
            Icon(Icons.Rounded.Menu, contentDescription = "Open navigation drawer")
        }
    }
}

internal fun topLevelDestinationFor(route: String?): Destination? = when {
    route == null -> null
    route == Destination.MONEY.route || route.startsWith("statement/") || route.startsWith("cycle/") -> Destination.MONEY
    route == Destination.TRIPS.route || route.startsWith("trip/") -> Destination.TRIPS
    route == Destination.PRIORITY.route || route.startsWith("priority/") -> Destination.PRIORITY
    route == Destination.DASHBOARD.route -> Destination.DASHBOARD
    route == Destination.MOOD.route -> Destination.MOOD
    route == Destination.INSIGHTS.route -> Destination.INSIGHTS
    route == Destination.CHAT.route -> Destination.CHAT
    else -> null
}

@Composable
internal fun LifeOsNavigationDrawer(
    currentRoute: String?,
    onDestinationSelected: (Destination) -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier.widthIn(max = 328.dp),
        drawerContainerColor = LifeOsColors.SurfaceElevated,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 20.dp),
        ) {
            Text(
                "LifeOS",
                style = MaterialTheme.typography.headlineLarge,
                color = LifeOsColors.OnBackground,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
            Text(
                "Choose an area",
                style = MaterialTheme.typography.bodyMedium,
                color = LifeOsColors.OnSurfaceMuted,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 2.dp, bottom = 18.dp),
            )
            navigationDestinations.forEach { destination ->
                NavigationDrawerItem(
                    selected = currentRoute == destination.route,
                    label = {
                        Column {
                            Text(destination.label, fontWeight = FontWeight.SemiBold)
                            Text(
                                destinationSupportingText(destination),
                                style = MaterialTheme.typography.labelSmall,
                                color = LifeOsColors.OnSurfaceMuted,
                            )
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    onClick = { onDestinationSelected(destination) },
                )
            }
        }
    }
}

private fun destinationSupportingText(destination: Destination): String = when (destination) {
    Destination.DASHBOARD -> "Daily scores"
    Destination.MOOD -> "Mood check-in"
    Destination.INSIGHTS -> "Patterns and trends"
    Destination.CHAT -> "Grounded answers"
    Destination.SETTINGS -> "Sync and preferences"
    Destination.MONEY -> "Spending and cards"
    Destination.PRIORITY -> "Important mail and schedules"
    Destination.TRIPS -> "Plans, places and memories"
}
