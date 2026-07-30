package com.lifeos.app.presentation.money

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.model.CurrentCardCycleDetails
import com.lifeos.app.domain.repository.MoneyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CurrentCycleDetailsUiState(
    val loading: Boolean = true,
    val details: CurrentCardCycleDetails? = null,
    val error: String? = null,
)

@HiltViewModel
class CurrentCycleDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MoneyRepository,
) : ViewModel() {
    private val bank: String = checkNotNull(savedStateHandle["bank"])
    private val cardLast4: String = checkNotNull(savedStateHandle["cardLast4"])
    private val _state = MutableStateFlow(CurrentCycleDetailsUiState())
    val state: StateFlow<CurrentCycleDetailsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repository.currentCardCycleDetails(bank, cardLast4) }
                .onSuccess { details ->
                    _state.update { it.copy(loading = false, details = details) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = error.message ?: "Couldn't load this billing cycle",
                        )
                    }
                }
        }
    }
}
