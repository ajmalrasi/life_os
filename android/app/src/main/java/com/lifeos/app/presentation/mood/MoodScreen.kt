package com.lifeos.app.presentation.mood

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifeos.app.presentation.components.QuickCheckInCard
import com.lifeos.app.presentation.components.ScreenHeader
import com.lifeos.app.presentation.components.lifeOsFieldColors
import com.lifeos.app.presentation.theme.LifeOsColors

@Composable
fun MoodScreen(viewModel: MoodViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fieldColors = lifeOsFieldColors()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LifeOsColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader("Daily pulse", "One tap is enough. Add a note only if something stands out.")

        QuickCheckInCard(
            selected = state.selected,
            savedLabel = state.savedMessage,
            enabled = !state.submitting,
            onSelect = viewModel::select,
        )

        TextButton(onClick = viewModel::toggleNote) {
            Text(
                if (state.showNote) "Hide note" else "+ Add a note (optional)",
                color = LifeOsColors.Primary,
            )
        }

        if (state.showNote) {
            OutlinedTextField(
                value = state.note,
                onValueChange = viewModel::onNoteChange,
                label = { Text("What stood out?") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors,
            )
            Text(
                "Re-tap an emoji to save with your note.",
                style = MaterialTheme.typography.bodySmall,
                color = LifeOsColors.OnSurfaceMuted,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
