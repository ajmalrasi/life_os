package com.lifeos.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lifeos.app.notifications.AppNotifications
import com.lifeos.app.presentation.LifeOsApp
import com.lifeos.app.presentation.theme.LifeOsTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The status bar sits on the light app canvas; the native Android
        // navigation controls share the navy bottom dock below.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(
                Color.rgb(0xD7, 0xE5, 0xF0),
                Color.rgb(0x11, 0x1D, 0x28),
            ),
        )
        // Deep-link target from a notification tap (e.g. the Pulse reminder).
        val startTab = intent?.getStringExtra(AppNotifications.EXTRA_OPEN_TAB)
        setContent {
            LifeOsTheme {
                LifeOsApp(startTab = startTab)
            }
        }
    }
}
