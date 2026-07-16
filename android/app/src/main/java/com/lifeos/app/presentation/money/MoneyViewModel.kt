package com.lifeos.app.presentation.money

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.model.CardStatement
import com.lifeos.app.domain.model.Transaction
import com.lifeos.app.domain.model.Repayment
import com.lifeos.app.domain.model.RepaymentCandidate
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

/** How the spend view is bucketed. All are always selectable. */
enum class SpendPeriod { DAY, WEEK, MONTH, SIX_MONTHS, YEAR, CUSTOM }

data class MoneyUiState(
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val error: String? = null,
    val period: SpendPeriod = SpendPeriod.MONTH,
    /** The period currently in view; moved by [prevPeriod]/[nextPeriod]. */
    val anchor: LocalDate = LocalDate.now(),
    /** Inclusive bounds of the user-picked range; only used when [period] == CUSTOM. */
    val customStart: LocalDate? = null,
    val customEnd: LocalDate? = null,
    /** All cached spend + income transactions in the trailing window (Room). */
    val transactions: List<Transaction> = emptyList(),
    /** Non-null when the user has tapped a category to drill into it. */
    val selectedCategory: String? = null,
    /** Multi-select category filter; empty = show everything. */
    val categoryFilter: Set<String> = emptySet(),
    // Demoted card-dues summary — loaded softly so a backend hiccup never blanks
    // the (offline, cache-driven) spend view.
    val totalDue: BigDecimal? = null,
    val nextDueDate: LocalDate? = null,
    /** Per-card statements with an outstanding due, soonest due date first. */
    val cardDues: List<CardStatement> = emptyList(),
    val repaymentDebit: Transaction? = null,
    val repaymentCandidates: List<RepaymentCandidate> = emptyList(),
    val selectedRepaymentCreditId: Int? = null,
    val repaymentLoading: Boolean = false,
    val repaymentSaving: Boolean = false,
    val repaymentError: String? = null,
    /** One-shot event consumed after its Undo snackbar is handled. */
    val completedRepayment: Repayment? = null,
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
                    cardDues = dues?.cards
                        ?.filter { c -> (c.totalDue ?: BigDecimal.ZERO) > BigDecimal.ZERO }
                        ?.sortedBy { c -> c.dueDate ?: LocalDate.MAX }
                        ?: it.cardDues,
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

    /** Switch bucketing. Resets to the current period and clears any drill-down.
     * The category filter is intentionally kept — it's an orthogonal control. */
    fun setPeriod(period: SpendPeriod) {
        _state.update { it.copy(period = period, anchor = LocalDate.now(), selectedCategory = null) }
    }

    /** Apply a user-picked date range and switch to the CUSTOM period. */
    fun setCustomRange(start: LocalDate, end: LocalDate) {
        val (s, e) = if (start.isAfter(end)) end to start else start to end
        _state.update {
            it.copy(period = SpendPeriod.CUSTOM, customStart = s, customEnd = e, selectedCategory = null)
        }
    }

    /** Toggle one category in the multi-select filter. */
    fun toggleCategoryFilter(category: String) {
        _state.update {
            val next = if (category in it.categoryFilter) it.categoryFilter - category
            else it.categoryFilter + category
            it.copy(categoryFilter = next, selectedCategory = null)
        }
    }

    fun clearCategoryFilter() {
        _state.update { it.copy(categoryFilter = emptySet(), selectedCategory = null) }
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

    fun beginRepayment(debit: Transaction) {
        if (debit.direction != "debit") return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    repaymentDebit = debit,
                    repaymentCandidates = emptyList(),
                    selectedRepaymentCreditId = null,
                    repaymentLoading = true,
                    repaymentSaving = false,
                    repaymentError = null,
                )
            }
            val result = runCatching { repository.repaymentCandidates(debit.id) }
            _state.update {
                val candidates = result.getOrDefault(emptyList())
                it.copy(
                    repaymentCandidates = candidates,
                    selectedRepaymentCreditId = candidates.firstOrNull()?.transaction?.id,
                    repaymentLoading = false,
                    repaymentError = result.exceptionOrNull()?.let { error ->
                        error.message ?: "Couldn't load repayment options"
                    },
                )
            }
        }
    }

    fun selectRepaymentCredit(id: Int) {
        _state.update { it.copy(selectedRepaymentCreditId = id, repaymentError = null) }
    }

    fun dismissRepayment() {
        if (_state.value.repaymentSaving) return
        _state.update {
            it.copy(
                repaymentDebit = null,
                repaymentCandidates = emptyList(),
                selectedRepaymentCreditId = null,
                repaymentLoading = false,
                repaymentError = null,
            )
        }
    }

    fun confirmRepayment() {
        val snapshot = _state.value
        val debitId = snapshot.repaymentDebit?.id ?: return
        val creditId = snapshot.selectedRepaymentCreditId ?: return
        if (snapshot.repaymentSaving) return
        viewModelScope.launch {
            _state.update { it.copy(repaymentSaving = true, repaymentError = null) }
            val result = runCatching { repository.markAsRepaid(debitId, creditId) }
            if (result.isSuccess) {
                val repayment = result.getOrThrow()
                runCatching { repository.refresh(transactionDays = TRANSACTION_WINDOW_DAYS) }
                _state.update {
                    it.copy(
                        repaymentDebit = null,
                        repaymentCandidates = emptyList(),
                        selectedRepaymentCreditId = null,
                        repaymentSaving = false,
                        completedRepayment = repayment,
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        repaymentSaving = false,
                        repaymentError = result.exceptionOrNull()?.message ?: "Couldn't mark this as repaid",
                    )
                }
            }
        }
    }

    fun undoRepayment(repaymentId: Int) {
        viewModelScope.launch {
            val result = runCatching { repository.undoRepayment(repaymentId) }
            if (result.isSuccess) {
                runCatching { repository.refresh(transactionDays = TRANSACTION_WINDOW_DAYS) }
            } else {
                _state.update {
                    it.copy(error = result.exceptionOrNull()?.message ?: "Couldn't undo repayment")
                }
            }
        }
    }

    fun consumeCompletedRepayment() {
        _state.update { it.copy(completedRepayment = null) }
    }

    private fun shift(anchor: LocalDate, period: SpendPeriod, steps: Long): LocalDate = when (period) {
        SpendPeriod.DAY -> anchor.plusDays(steps)
        SpendPeriod.WEEK -> anchor.plusWeeks(steps)
        SpendPeriod.MONTH -> anchor.plusMonths(steps)
        SpendPeriod.SIX_MONTHS -> anchor.plusMonths(6 * steps)
        SpendPeriod.YEAR -> anchor.plusYears(steps)
        SpendPeriod.CUSTOM -> anchor // fixed range; user re-picks instead of paging
    }

    private companion object {
        // Wide enough to cover "This year" and paging back through 6-month views.
        const val TRANSACTION_WINDOW_DAYS = 400
    }
}
