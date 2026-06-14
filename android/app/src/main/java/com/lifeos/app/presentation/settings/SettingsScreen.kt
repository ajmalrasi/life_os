package com.lifeos.app.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Point LifeOS at your backend (the FastAPI server on your Jetson Orin Nano). " +
                "Use the device's LAN address, e.g. http://192.168.1.50:8000/",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = viewModel::onUrlChange,
            label = { Text("Backend base URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) {
            Text("Save")
        }

        if (state.saved) {
            Text("Saved.", color = MaterialTheme.colorScheme.primary)
        }

        Text(
            "Tip: 10.0.2.2 reaches your computer from the Android emulator. " +
                "OpenClaw runs behind the backend; configure its address in the backend's .env.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
