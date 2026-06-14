package com.lifeos.app.presentation.mood

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.model.MoodCheckin
import com.lifeos.app.domain.repository.AnalyticsRepository
import com.lifeos.app.domain.repository.MoodRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class MoodUiState(
    val mood: Int = 3,
    val energy: Int = 3,
    val focus: Int = 3,
    val stress: Int = 3,
    val note: String = "",
    val submitting: Boolean = false,
    val saved: Boolean = false,
)

@HiltViewModel
class MoodViewModel @Inject constructor(
    private val moodRepository: MoodRepository,
    private val analyticsRepository: AnalyticsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MoodUiState())
    val state: StateFlow<MoodUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { moodRepository.refresh(7) }
        }
    }

    fun update(transform: MoodUiState.() -> MoodUiState) {
        _state.value = _state.value.transform().copy(saved = false)
    }

    fun submit() {
        val s = _state.value
        viewModelScope.launch {
            _state.value = s.copy(submitting = true)
            runCatching {
                moodRepository.submit(
                    MoodCheckin(
                        day = LocalDate.now(),
                        mood = s.mood,
                        energy = s.energy,
                        focus = s.focus,
                        stress = s.stress,
                        note = s.note.ifBlank { null },
                    )
                )
                // Recompute so today's state reflects the new check-in.
                analyticsRepository.recompute(60)
            }
            _state.value = _state.value.copy(submitting = false, saved = true)
        }
    }
}
