package com.lifeos.app.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    DASHBOARD("dashboard", "Today", Icons.Rounded.WbSunny),
    MOOD("mood", "Pulse", Icons.Rounded.Favorite),
    MONEY("money", "Money", Icons.Rounded.AccountBalanceWallet),
    PRIORITY("priority", "Priority", Icons.Rounded.Mail),
    INSIGHTS("insights", "Insights", Icons.Rounded.Insights),
    CHAT("chat", "Ask", Icons.Rounded.AutoAwesome),
    SETTINGS("settings", "Settings", Icons.Rounded.Tune),
}

private val moreDestinations = listOf(
    Destination.PRIORITY,
    Destination.DASHBOARD,
    Destination.MOOD,
    Destination.INSIGHTS,
    Destination.CHAT,
    Destination.SETTINGS,
)
private const val STATEMENT_DETAILS_ROUTE = "statement/{statementId}"
private const val CURRENT_CYCLE_DETAILS_ROUTE = "cycle/{bank}/{cardLast4}"
private const val PRIORITY_DETAILS_ROUTE = "priority/{itemId}"
internal const val DEFAULT_DESTINATION_ROUTE = "money"

@Composable
fun LifeOsApp(startTab: String? = null, openRequestId: Long = 0L, openPriorityItemId: Int? = null) {
    val navController = rememberNavController()
    var showMore by remember { mutableStateOf(false) }
    var moneyOverviewRequest by remember { mutableStateOf(0L) }

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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = LifeOsColors.Background,
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = DEFAULT_DESTINATION_ROUTE,
            modifier = Modifier.padding(contentPadding),
        ) {
            composable(Destination.MONEY.route) {
                MoneyScreen(
                    overviewRequestId = moneyOverviewRequest,
                    onOpenMore = { showMore = true },
                    onOpenStatement = { id -> navController.navigate("statement/$id") },
                    onOpenCurrentCycle = { bank, cardLast4 ->
                        navController.navigate("cycle/$bank/$cardLast4")
                    },
                )
            }
            composable(Destination.DASHBOARD.route) {
                DashboardScreen(onOpenSettings = { navController.navigate(Destination.SETTINGS.route) })
            }
            composable(Destination.MOOD.route) { MoodScreen() }
            composable(Destination.PRIORITY.route) {
                PriorityScreen(onOpenItem = { id -> navController.navigate("priority/$id") })
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
                PriorityDetailsScreen(onBack = { navController.popBackStack() })
            }
        }
    }

    if (showMore) {
        LifeOsMoreSheet(
            onDismiss = { showMore = false },
            onDestinationSelected = { destination ->
                showMore = false
                navController.navigate(destination.route) { launchSingleTop = true }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LifeOsMoreSheet(
    onDismiss: () -> Unit,
    onDestinationSelected: (Destination) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LifeOsColors.SurfaceElevated,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                "LifeOS",
                style = MaterialTheme.typography.headlineMedium,
                color = LifeOsColors.OnBackground,
            )
            Text(
                "Everything outside your daily Money view",
                style = MaterialTheme.typography.bodySmall,
                color = LifeOsColors.OnSurfaceMuted,
                modifier = Modifier.padding(top = 3.dp, bottom = 12.dp),
            )
            moreDestinations.forEachIndexed { index, destination ->
                TextButton(
                    onClick = { onDestinationSelected(destination) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = LifeOsColors.Primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                destination.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = LifeOsColors.OnBackground,
                            )
                            Text(
                                destinationSupportingText(destination),
                                style = MaterialTheme.typography.labelSmall,
                                color = LifeOsColors.OnSurfaceMuted,
                            )
                        }
                    }
                }
                if (index != moreDestinations.lastIndex) {
                    HorizontalDivider(color = LifeOsColors.SurfaceBorder)
                }
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
}
