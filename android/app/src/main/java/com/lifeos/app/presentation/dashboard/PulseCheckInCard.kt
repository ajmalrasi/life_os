package com.lifeos.app.presentation.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeos.app.domain.model.DayPulse
import com.lifeos.app.presentation.components.LifeOsCard
import com.lifeos.app.presentation.components.lifeOsFieldColors
import com.lifeos.app.presentation.theme.LifeOsColors

/**
 * Today's Pulse check-in, inline on Today instead of its own page. One tap
 * saves; tapping another option changes it. The note is optional and has its
 * own Save, so it never depends on re-tapping a face.
 */
@Composable
internal fun PulseCheckInCard(
    selected: DayPulse?,
    note: String,
    noteDirty: Boolean,
    message: String?,
    submitting: Boolean,
    onSelect: (DayPulse) -> Unit,
    onNoteChange: (String) -> Unit,
    onSaveNote: () -> Unit,
    onDiscardNote: () -> Unit,
) {
    var editingNote by rememberSaveable { mutableStateOf(false) }
    LifeOsCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("PULSE", style = MaterialTheme.typography.labelSmall, color = LifeOsColors.OnSurfaceMuted)
                Text(
                    if (selected == null) "How's today going?" else "Today feels ${selected.label.lowercase()}",
                    style = MaterialTheme.typography.titleMedium,
                    color = LifeOsColors.OnBackground,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayPulse.entries.forEach { pulse ->
                    PulseOption(
                        pulse = pulse,
                        selected = pulse == selected,
                        enabled = !submitting,
                        onClick = { onSelect(pulse) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            when {
                selected == null -> Text(
                    "One tap saves. Change it anytime today.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LifeOsColors.OnSurfaceMuted,
                )
                editingNote || noteDirty -> {
                    OutlinedTextField(
                        value = note,
                        onValueChange = onNoteChange,
                        placeholder = { Text("What stood out today?") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                        shape = RoundedCornerShape(14.dp),
                        colors = lifeOsFieldColors(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editingNote = false; onDiscardNote() }) {
                            Text("Cancel", color = LifeOsColors.OnSurfaceMuted)
                        }
                        TextButton(
                            onClick = { editingNote = false; onSaveNote() },
                            enabled = noteDirty && !submitting,
                        ) {
                            Text("Save note", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                note.isNotBlank() -> Row(verticalAlignment = Alignment.Top) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LifeOsColors.OnBackground,
                        modifier = Modifier.weight(1f).padding(top = 12.dp),
                    )
                    TextButton(onClick = { editingNote = true }) { Text("Edit") }
                }
                else -> TextButton(
                    onClick = { editingNote = true },
                    modifier = Modifier.heightIn(min = 44.dp),
                ) { Text("+ Add a note") }
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (it.startsWith("Couldn't")) LifeOsColors.Warning else LifeOsColors.Positive,
                )
            }
        }
    }
}

@Composable
private fun PulseOption(
    pulse: DayPulse,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = pulse.accent()
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.14f) else LifeOsColors.Background)
            .border(if (selected) 2.dp else 1.dp, if (selected) accent else LifeOsColors.SurfaceBorder, shape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = pulse.label
            }
            .heightIn(min = 64.dp)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text(pulse.emoji, fontSize = 24.sp)
        Text(
            pulse.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) accent else LifeOsColors.OnSurfaceMuted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

private fun DayPulse.accent(): Color = when (this) {
    DayPulse.ROUGH -> LifeOsColors.ScoreLow
    DayPulse.MEH -> LifeOsColors.OnSurfaceMuted
    DayPulse.GOOD -> LifeOsColors.Secondary
    DayPulse.FLOW -> LifeOsColors.Primary
}
