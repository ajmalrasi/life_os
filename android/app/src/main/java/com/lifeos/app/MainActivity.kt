package com.lifeos.app

import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.lifeos.app.notifications.AppNotifications
import com.lifeos.app.presentation.LifeOsApp
import com.lifeos.app.presentation.theme.LifeOsTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val openRequest = mutableStateOf(OpenRequest(null, 0L))

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
            id = System.nanoTime(),
        )
        setContent {
            LifeOsTheme {
                val request = openRequest.value
                LifeOsApp(startTab = request.route, openRequestId = request.id)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequest.value = OpenRequest(
            route = intent.getStringExtra(AppNotifications.EXTRA_OPEN_TAB),
            id = System.nanoTime(),
        )
    }
}

private data class OpenRequest(val route: String?, val id: Long)
