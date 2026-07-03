package com.lifeos.app.presentation.money

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.model.Transaction
import com.lifeos.app.domain.repository.MoneyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject

/** How the spend view is bucketed. All three are always selectable. */
enum class SpendPeriod { DAY, WEEK, MONTH }

data class MoneyUiState(
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val error: String? = null,
    val period: SpendPeriod = SpendPeriod.MONTH,
    /** The period currently in view; moved by [prevPeriod]/[nextPeriod]. */
    val anchor: LocalDate = LocalDate.now(),
    /** All cached spend + income transactions in the trailing window (Room). */
    val transactions: List<Transaction> = emptyList(),
    /** Non-null when the user has tapped a category to drill into it. */
    val selectedCategory: String? = null,
    // Demoted card-dues summary — loaded softly so a backend hiccup never blanks
    // the (offline, cache-driven) spend view.
    val totalDue: BigDecimal? = null,
    val nextDueDate: LocalDate? = null,
)

@HiltViewModel
class MoneyViewModel @Inject constructor(
    private val repository: MoneyRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MoneyUiState())
    val state: StateFlow<MoneyUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeTransactions(TRANSACTION_WINDOW_DAYS).collect { txns ->
                _state.update { it.copy(transactions = txns) }
            }
        }
        load()
    }

    /** Refresh the cache from the backend; dues load softly and never fail the
     * spend view, which is served from the local Room cache. */
    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val refresh = runCatching { repository.refresh(transactionDays = TRANSACTION_WINDOW_DAYS) }
            val dues = runCatching { repository.analysis() }.getOrNull()
            _state.update {
                it.copy(
                    loading = false,
                    error = refresh.exceptionOrNull()?.let { e -> e.message ?: "Couldn't reach the server" },
                    totalDue = dues?.totalDueAllCards ?: it.totalDue,
                    nextDueDate = dues?.nextDueDate ?: it.nextDueDate,
                )
            }
        }
    }

    /** Pull-to-refresh: ask the backend to check for new emails now, then reload. */
    fun syncNow() {
        viewModelScope.launch {
            _state.update { it.copy(syncing = true) }
            runCatching { repository.syncNow() }
            _state.update { it.copy(syncing = false) }
            load()
        }
    }

    /** Switch bucketing. Resets to the current period and clears any drill-down. */
    fun setPeriod(period: SpendPeriod) {
        _state.update { it.copy(period = period, anchor = LocalDate.now(), selectedCategory = null) }
    }

    fun prevPeriod() {
        _state.update { it.copy(anchor = shift(it.anchor, it.period, -1), selectedCategory = null) }
    }

    /** Step forward one period, clamped so we never scroll into the future. */
    fun nextPeriod() {
        _state.update {
            val moved = shift(it.anchor, it.period, +1)
            val clamped = if (moved.isAfter(LocalDate.now())) LocalDate.now() else moved
            it.copy(anchor = clamped, selectedCategory = null)
        }
    }

    fun selectCategory(category: String?) {
        _state.update { it.copy(selectedCategory = category) }
    }

    private fun shift(anchor: LocalDate, period: SpendPeriod, steps: Long): LocalDate = when (period) {
        SpendPeriod.DAY -> anchor.plusDays(steps)
        SpendPeriod.WEEK -> anchor.plusWeeks(steps)
        SpendPeriod.MONTH -> anchor.plusMonths(steps)
    }

    private companion object {
        // Wide enough to page back ~6 months of Day/Week/Month views offline.
        const val TRANSACTION_WINDOW_DAYS = 180
    }
}
