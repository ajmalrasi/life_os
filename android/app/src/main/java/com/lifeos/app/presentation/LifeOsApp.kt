package com.lifeos.app.presentation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lifeos.app.presentation.chat.ChatScreen
import com.lifeos.app.presentation.dashboard.DashboardScreen
import com.lifeos.app.presentation.insights.InsightsScreen
import com.lifeos.app.presentation.mood.MoodScreen
import com.lifeos.app.presentation.settings.SettingsScreen

enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    DASHBOARD("dashboard", "Today", Icons.Filled.Dashboard),
    MOOD("mood", "Check-in", Icons.Filled.Mood),
    INSIGHTS("insights", "Insights", Icons.Filled.Insights),
    CHAT("chat", "Ask", Icons.AutoMirrored.Filled.Chat),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

@Composable
fun LifeOsApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { dest ->
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
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.DASHBOARD.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Destination.DASHBOARD.route) { DashboardScreen() }
            composable(Destination.MOOD.route) { MoodScreen() }
            composable(Destination.INSIGHTS.route) { InsightsScreen() }
            composable(Destination.CHAT.route) { ChatScreen() }
            composable(Destination.SETTINGS.route) { SettingsScreen() }
        }
    }
}
