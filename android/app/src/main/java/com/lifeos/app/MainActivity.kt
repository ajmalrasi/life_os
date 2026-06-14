package com.lifeos.app

import android.os.Bundle
import androidx.activity.ComponentActivity
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
        enableEdgeToEdge()
        // Deep-link target from a notification tap (e.g. the Pulse reminder).
        val startTab = intent?.getStringExtra(AppNotifications.EXTRA_OPEN_TAB)
        setContent {
            LifeOsTheme {
                LifeOsApp(startTab = startTab)
            }
        }
    }
}
