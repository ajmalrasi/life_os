package com.lifeos.app.presentation.priority

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.model.PriorityDetails
import com.lifeos.app.domain.repository.PriorityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class PriorityDetailsUiState(
    val details: PriorityDetails? = null,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val message: String? = null,
    val fullEmail: String? = null,
    val fullEmailLoading: Boolean = false,
    val fullEmailError: String? = null,
)

@HiltViewModel
class PriorityDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PriorityRepository,
) : ViewModel() {
    private val itemId: Int = checkNotNull(savedStateHandle["itemId"])
    private val _state = MutableStateFlow(PriorityDetailsUiState())
    val state: StateFlow<PriorityDetailsUiState> = _state.asStateFlow()

    init {
        load()
        // Neither the Gmail app nor Gmail web can be opened at one message by
        // another app, so the source email is always shown here.
        loadFullEmail()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, message = null)
            runCatching { repository.details(itemId) }
                .onSuccess { _state.value = PriorityDetailsUiState(details = it, loading = false) }
                .onFailure {
                    _state.value = PriorityDetailsUiState(
                        loading = false,
                        message = it.message ?: "This Priority item could not be opened.",
                    )
                }
        }
    }

    fun loadFullEmail() {
        if (_state.value.fullEmailLoading || _state.value.fullEmail != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(fullEmailLoading = true, fullEmailError = null)
            runCatching { repository.fullMessage(itemId) }
                .onSuccess { body ->
                    _state.value = _state.value.copy(fullEmail = body, fullEmailLoading = false)
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        fullEmailLoading = false,
                        fullEmailError = it.message ?: "The email could not be loaded from Gmail.",
                    )
                }
        }
    }

    fun markDone() = update(state = "done", successMessage = "Marked done")

    fun snoozeOneDay() = update(
        snoozedUntil = Instant.now().plus(1, ChronoUnit.DAYS),
        successMessage = "Snoozed until tomorrow",
    )

    fun markNotImportant() {
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, message = null)
            runCatching { repository.feedback(itemId, "not_important") }
                .onSuccess { updated ->
                    val current = _state.value.details
                    _state.value = _state.value.copy(
                        details = current?.copy(item = updated),
                        saving = false,
                        message = "Marked not important",
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(saving = false, message = it.message)
                }
        }
    }

    private fun update(
        state: String? = null,
        snoozedUntil: Instant? = null,
        successMessage: String,
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, message = null)
            runCatching { repository.update(itemId, state = state, snoozedUntil = snoozedUntil) }
                .onSuccess { updated ->
                    val current = _state.value.details
                    _state.value = _state.value.copy(
                        details = current?.copy(item = updated),
                        saving = false,
                        message = successMessage,
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(saving = false, message = it.message)
                }
        }
    }
}
