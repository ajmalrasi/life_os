package com.lifeos.app.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lifeos.app.presentation.chat.ChatScreen
import com.lifeos.app.presentation.dashboard.DashboardScreen
import com.lifeos.app.presentation.insights.InsightsScreen
import com.lifeos.app.presentation.money.MoneyScreen
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
            val settingsOpen = current?.hierarchy?.any { it.route == Destination.SETTINGS.route } == true
            if (!settingsOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LifeOsColors.Background)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    NavigationBar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .clip(RoundedCornerShape(22.dp)),
                        containerColor = LifeOsColors.Surface,
                        tonalElevation = 2.dp,
                        windowInsets = WindowInsets(0, 0, 0, 0),
                    ) {
                        primaryDestinations.forEach { dest ->
                            val selected = current?.hierarchy?.any { it.route == dest.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(dest.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                alwaysShowLabel = false,
                                icon = { Icon(dest.icon, contentDescription = dest.label, modifier = Modifier.size(22.dp)) },
                                label = { Text(dest.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = LifeOsColors.Primary,
                                    selectedTextColor = LifeOsColors.Primary,
                                    indicatorColor = LifeOsColors.PrimarySoft,
                                    unselectedIconColor = LifeOsColors.OnSurfaceMuted,
                                    unselectedTextColor = LifeOsColors.OnSurfaceMuted,
                                ),
                            )
                        }
                    }
                }
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
            composable(Destination.MONEY.route) { MoneyScreen() }
            composable(Destination.INSIGHTS.route) { InsightsScreen() }
            composable(Destination.CHAT.route) { ChatScreen() }
            composable(Destination.SETTINGS.route) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
