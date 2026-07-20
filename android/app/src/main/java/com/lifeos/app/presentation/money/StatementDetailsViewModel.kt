package com.lifeos.app.presentation.money

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.model.CardStatementDetails
import com.lifeos.app.domain.repository.MoneyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StatementDetailsUiState(
    val loading: Boolean = true,
    val details: CardStatementDetails? = null,
    val error: String? = null,
    val savingPaid: Boolean = false,
)

@HiltViewModel
class StatementDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MoneyRepository,
) : ViewModel() {
    private val statementId: Int = checkNotNull(savedStateHandle["statementId"])
    private val _state = MutableStateFlow(StatementDetailsUiState())
    val state: StateFlow<StatementDetailsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repository.statementDetails(statementId) }
                .onSuccess { details ->
                    _state.update { it.copy(loading = false, details = details) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = error.message ?: "Couldn't load this statement",
                        )
                    }
                }
        }
    }

    fun togglePaid() {
        val current = _state.value.details?.statement ?: return
        if (_state.value.savingPaid) return
        viewModelScope.launch {
            _state.update { it.copy(savingPaid = true, error = null) }
            runCatching { repository.updateStatementPaid(current.id, current.status != "paid") }
                .onSuccess { updated ->
                    _state.update { state ->
                        state.copy(
                            savingPaid = false,
                            details = state.details?.copy(statement = updated),
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            savingPaid = false,
                            error = error.message ?: "Couldn't update payment status",
                        )
                    }
                }
        }
    }
}
