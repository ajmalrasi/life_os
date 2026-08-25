package com.lifeos.app.presentation.priority

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.model.PriorityItem
import com.lifeos.app.domain.repository.PriorityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PriorityUiState(
    val items: List<PriorityItem> = emptyList(),
    val filter: String = "all",
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val message: String? = null,
    val error: Boolean = false,
)

private data class PriorityControls(
    val filter: String = "all",
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val message: String? = null,
    val error: Boolean = false,
)

@HiltViewModel
class PriorityViewModel @Inject constructor(
    private val repository: PriorityRepository,
) : ViewModel() {
    private val controls = MutableStateFlow(PriorityControls())

    val state: StateFlow<PriorityUiState> = combine(
        repository.observeItems(),
        controls,
    ) { items, control ->
        PriorityUiState(
            items = filterItems(items, control.filter),
            filter = control.filter,
            loading = control.loading,
            syncing = control.syncing,
            message = control.message,
            error = control.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PriorityUiState())

    init {
        refresh()
    }

    fun setFilter(filter: String) {
        controls.update { it.copy(filter = filter) }
    }

    fun refresh() {
        viewModelScope.launch {
            controls.update { it.copy(loading = true, error = false, message = null) }
            runCatching { repository.refresh() }
                .onSuccess { controls.update { it.copy(loading = false) } }
                .onFailure { error ->
                    controls.update {
                        it.copy(
                            loading = false,
                            error = true,
                            message = error.message ?: "Priority could not be refreshed.",
                        )
                    }
                }
        }
    }

    fun syncNow() {
        if (controls.value.syncing) return
        viewModelScope.launch {
            controls.update { it.copy(syncing = true, error = false, message = null) }
            runCatching { repository.syncNow() }
                .onSuccess { result ->
                    controls.update {
                        it.copy(
                            syncing = false,
                            error = !result.successful,
                            message = result.message,
                        )
                    }
                }
                .onFailure { error ->
                    controls.update {
                        it.copy(
                            syncing = false,
                            error = true,
                            message = error.message ?: "The Priority mailbox could not be checked.",
                        )
                    }
                }
        }
    }

    companion object {
        fun filterItems(items: List<PriorityItem>, filter: String): List<PriorityItem> = when (filter) {
            "action" -> items.filter { it.state == "needs_action" }
            "scheduled" -> items.filter { it.state == "scheduled" }
            "personal", "work", "interview", "security" -> items.filter { filter in it.labels }
            "review" -> items.filter { it.importance == "review" }
            else -> items
        }
    }
}
