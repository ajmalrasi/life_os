package com.lifeos.app

import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.lifeos.app.domain.repository.SettingsRepository
import com.lifeos.app.notifications.AppNotifications
import com.lifeos.app.presentation.LifeOsApp
import com.lifeos.app.presentation.theme.LifeOsColors
import com.lifeos.app.presentation.theme.LifeOsTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository

    private val openRequest = mutableStateOf(OpenRequest(null, null, 0L))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // With Money using the full screen (no bottom dock), both system bars
        // blend into the app canvas.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(
                Color.rgb(0xF4, 0xF7, 0xFB),
                Color.rgb(0x0E, 0x16, 0x20),
            ),
        )
        // Deep-link target from a notification tap (e.g. the Pulse reminder).
        openRequest.value = OpenRequest(
            route = intent?.getStringExtra(AppNotifications.EXTRA_OPEN_TAB),
            priorityItemId = intent?.priorityItemId(),
            id = System.nanoTime(),
        )
        setContent {
            LifeOsTheme {
                val startupDestination by produceState<String?>(initialValue = null) {
                    value = settingsRepository.startupDestination()
                }
                val startDestination = startupDestination
                if (startDestination != null) {
                    val request = openRequest.value
                    LifeOsApp(
                        startDestination = startDestination,
                        startTab = request.route,
                        openRequestId = request.id,
                        openPriorityItemId = request.priorityItemId,
                        onDestinationChanged = { dest ->
                            lifecycleScope.launch {
                                settingsRepository.setLastUsedTab(dest.route)
                            }
                        },
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().background(LifeOsColors.Background))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequest.value = OpenRequest(
            route = intent.getStringExtra(AppNotifications.EXTRA_OPEN_TAB),
            priorityItemId = intent.priorityItemId(),
            id = System.nanoTime(),
        )
    }
}

private fun Intent.priorityItemId(): Int? {
    val key = AppNotifications.EXTRA_OPEN_ITEM_ID
    return (
        getStringExtra(key)?.toIntOrNull()
            ?: getIntExtra(key, -1).takeIf { it >= 0 }
        )?.takeIf { it >= 0 }
}

private data class OpenRequest(val route: String?, val priorityItemId: Int?, val id: Long)
