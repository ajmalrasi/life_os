package com.lifeos.app.presentation.mood

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.model.DayPulse
import com.lifeos.app.domain.repository.AnalyticsRepository
import com.lifeos.app.domain.repository.MoodRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class MoodUiState(
    val selected: DayPulse? = null,
    val note: String = "",
    val showNote: Boolean = false,
    val submitting: Boolean = false,
    val savedMessage: String? = null,
)

@HiltViewModel
class MoodViewModel @Inject constructor(
    private val moodRepository: MoodRepository,
    private val analyticsRepository: AnalyticsRepository,
) : ViewModel() {

    private val transient = MutableStateFlow(TransientState())

    val state: StateFlow<MoodUiState> = combine(
        moodRepository.observe(7),
        transient,
    ) { checkins, t ->
        val todayPulse = checkins.firstOrNull { it.day == LocalDate.now() }?.let { DayPulse.fromCheckin(it) }
        MoodUiState(
            selected = t.selected ?: todayPulse,
            note = t.note,
            showNote = t.showNote,
            submitting = t.submitting,
            savedMessage = t.savedMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MoodUiState())

    init {
        viewModelScope.launch { runCatching { moodRepository.refresh(7) } }
    }

    fun select(pulse: DayPulse) {
        transient.value = transient.value.copy(selected = pulse, savedMessage = null)
        submit(pulse)
    }

    fun toggleNote() {
        transient.value = transient.value.copy(showNote = !transient.value.showNote)
    }

    fun onNoteChange(note: String) {
        transient.value = transient.value.copy(note = note, savedMessage = null)
    }

    fun submit(pulse: DayPulse? = null) {
        val selected = pulse ?: transient.value.selected ?: return
        viewModelScope.launch {
            transient.value = transient.value.copy(submitting = true)
            runCatching {
                moodRepository.submit(selected.toCheckin(transient.value.note.ifBlank { null }))
                analyticsRepository.recompute(60)
            }
            transient.value = transient.value.copy(
                submitting = false,
                savedMessage = "${selected.emoji} ${selected.label} — logged for today",
            )
        }
    }

    private data class TransientState(
        val selected: DayPulse? = null,
        val note: String = "",
        val showNote: Boolean = false,
        val submitting: Boolean = false,
        val savedMessage: String? = null,
    )
}
