package com.lifeos.app.presentation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.lifeos.app.presentation.chat.ChatScreen
import com.lifeos.app.presentation.dashboard.DashboardScreen
import com.lifeos.app.presentation.insights.InsightsScreen
import com.lifeos.app.presentation.money.MoneyScreen
import com.lifeos.app.presentation.money.StatementDetailsScreen
import com.lifeos.app.presentation.mood.MoodScreen
import com.lifeos.app.presentation.settings.SettingsScreen
import com.lifeos.app.presentation.theme.LifeOsColors

enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    DASHBOARD("dashboard", "Today", Icons.Rounded.WbSunny),
    MOOD("mood", "Pulse", Icons.Rounded.Favorite),
    MONEY("money", "Money", Icons.Rounded.AccountBalanceWallet),
    INSIGHTS("insights", "Insights", Icons.Rounded.Insights),
    CHAT("chat", "Ask", Icons.Rounded.AutoAwesome),
    SETTINGS("settings", "Settings", Icons.Rounded.Tune),
}

private val primaryDestinations = Destination.entries.filter { it != Destination.SETTINGS }
private const val STATEMENT_DETAILS_ROUTE = "statement/{statementId}"

@Composable
fun LifeOsApp(startTab: String? = null) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination

    // Deep-link: if launched from a notification targeting a tab, navigate once.
    LaunchedEffect(startTab) {
        val dest = Destination.entries.firstOrNull { it.route == startTab }
        if (dest != null) {
            navController.navigate(dest.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        containerColor = LifeOsColors.Background,
        bottomBar = {
            val secondaryScreenOpen = current?.hierarchy?.any {
                it.route == Destination.SETTINGS.route || it.route == STATEMENT_DETAILS_ROUTE
            } == true
            if (!secondaryScreenOpen) {
                LifeOsBottomNavigation(
                    selectedRoute = current?.route,
                    onDestinationSelected = { dest ->
                        navController.navigate(dest.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.DASHBOARD.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.DASHBOARD.route) {
                DashboardScreen(onOpenSettings = { navController.navigate(Destination.SETTINGS.route) })
            }
            composable(Destination.MOOD.route) { MoodScreen() }
            composable(Destination.MONEY.route) {
                MoneyScreen(onOpenStatement = { id -> navController.navigate("statement/$id") })
            }
            composable(Destination.INSIGHTS.route) { InsightsScreen() }
            composable(Destination.CHAT.route) { ChatScreen() }
            composable(Destination.SETTINGS.route) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = STATEMENT_DETAILS_ROUTE,
                arguments = listOf(navArgument("statementId") { type = NavType.IntType }),
            ) {
                StatementDetailsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * A flat, full-width dock that continues behind Android's native navigation
 * controls. Each destination owns an equal-width tap target; selection is
 * communicated with a slim marker, brighter icon, and stronger label.
 */
@Composable
private fun LifeOsBottomNavigation(
    selectedRoute: String?,
    onDestinationSelected: (Destination) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = LifeOsColors.NavigationSurface,
        shadowElevation = 8.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(78.dp)
                    .padding(horizontal = 6.dp),
            ) {
                primaryDestinations.forEach { destination ->
                    val selected = destination.route == selectedRoute
                    val contentColor by animateColorAsState(
                        targetValue = if (selected) {
                            LifeOsColors.NavigationSelected
                        } else {
                            LifeOsColors.NavigationUnselected
                        },
                        label = "bottom_navigation_color",
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(
                                selected = selected,
                                onClick = { onDestinationSelected(destination) },
                                role = Role.Tab,
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(28.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (selected) LifeOsColors.NavigationIndicator else Color.Transparent
                                ),
                        )
                        Spacer(Modifier.height(8.dp))
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = destination.label,
                            modifier = Modifier.size(22.dp),
                            tint = contentColor,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = destination.label,
                            color = contentColor,
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars),
            )
        }
    }
}
