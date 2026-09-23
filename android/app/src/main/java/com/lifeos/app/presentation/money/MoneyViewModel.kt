package com.lifeos.app.presentation.money

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.model.Budget
import com.lifeos.app.domain.model.CardStatement
import com.lifeos.app.domain.model.RecurringCharge
import com.lifeos.app.domain.model.CurrentCardCycle
import com.lifeos.app.domain.model.Transaction
import com.lifeos.app.domain.model.Repayment
import com.lifeos.app.domain.model.RepaymentCandidate
import com.lifeos.app.domain.model.Refund
import com.lifeos.app.domain.repository.MoneyRepository
import com.lifeos.app.domain.repository.SettingsRepository
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

/** Stable local destinations inside the Money-first experience. */
enum class MoneySection { OVERVIEW, TRANSACTIONS, CARDS }

/** Ordering for the main Transactions list. */
enum class TransactionSort { RECENT, OLDEST, HIGHEST, LOWEST }

internal val EMI_SPEND_CATEGORIES = setOf("emi", "emi_fees")

data class MoneyUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val section: MoneySection = MoneySection.OVERVIEW,
    val transactionSort: TransactionSort = TransactionSort.RECENT,
    val lastSyncAt: Long? = null,
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
    /** EMI remains recorded but is hidden from the everyday-spend view by default. */
    val includeEmi: Boolean = false,
    /** Null = all spend; ALL_BANK_UPI_FILTER = every proven savings account;
     * otherwise the canonical bank + account-suffix key. */
    val bankUpiFilter: String? = null,
    // Demoted card-dues summary, loaded softly so a backend hiccup never blanks
    // the (offline, cache-driven) spend view.
    val totalDue: BigDecimal? = null,
    val nextDueDate: LocalDate? = null,
    /** Per-card statements with an outstanding due, soonest due date first. */
    val cardDues: List<CardStatement> = emptyList(),
    /** Per-card open billing cycles, computed by the backend and cached in Room. */
    val currentCardCycles: List<CurrentCardCycle> = emptyList(),
    val repaymentDebit: Transaction? = null,
    val repaymentCandidates: List<RepaymentCandidate> = emptyList(),
    val selectedRepaymentCreditId: Int? = null,
    val repaymentLoading: Boolean = false,
    val repaymentSaving: Boolean = false,
    val repaymentError: String? = null,
    /** One-shot event consumed after its Undo snackbar is handled. */
    val completedRepayment: Repayment? = null,
    val refundDebit: Transaction? = null,
    val refundSaving: Boolean = false,
    val refundError: String? = null,
    /** One-shot event consumed after its Undo snackbar is handled. */
    val completedRefund: Refund? = null,
    val categoryEditTransaction: Transaction? = null,
    val categorySaving: Boolean = false,
    val categoryError: String? = null,
    /** Cached monthly budget limits (Stage 4b); pace is computed on-device. */
    val budgets: List<Budget> = emptyList(),
    /** Non-null while the "set budget" dialog is open for this category. */
    val budgetEditCategory: String? = null,
    val budgetSaving: Boolean = false,
    val budgetError: String? = null,
    /** Detected recurring charges (Stage 4c); fetched live, not cached. */
    val recurringCharges: List<RecurringCharge> = emptyList(),
)

@HiltViewModel
class MoneyViewModel @Inject constructor(
    private val repository: MoneyRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MoneyUiState())
    val state: StateFlow<MoneyUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.lastSyncAt.collect { lastSyncAt ->
                _state.update { it.copy(lastSyncAt = lastSyncAt) }
            }
        }
        viewModelScope.launch {
            repository.observeTransactions(TRANSACTION_WINDOW_DAYS).collect { txns ->
                _state.update { it.copy(transactions = txns) }
            }
        }
        // A paid-state change from the statement details screen updates Room;
        // observing the shared cache makes the due footer correct immediately
        // when the user navigates back.
        viewModelScope.launch {
            repository.observeStatements(STATEMENT_CACHE_MONTHS).collect { statements ->
                val today = LocalDate.now()
                val active = statements
                    .filter { statement ->
                        statement.paidAt == null &&
                            !(statement.dueDate?.isBefore(today) ?: false) &&
                            (statement.totalDue ?: BigDecimal.ZERO) > BigDecimal.ZERO
                    }
                    .sortedBy { statement -> statement.dueDate ?: LocalDate.MAX }
                _state.update {
                    it.copy(
                        cardDues = active,
                        totalDue = active.fold(BigDecimal.ZERO) { total, statement ->
                            total + (statement.totalDue ?: BigDecimal.ZERO)
                        },
                        nextDueDate = active.mapNotNull { statement -> statement.dueDate }.minOrNull(),
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.observeCurrentCardCycles().collect { cycles ->
                _state.update { it.copy(currentCardCycles = cycles) }
            }
        }
        viewModelScope.launch {
            repository.observeBudgets().collect { budgets ->
                _state.update { it.copy(budgets = budgets) }
            }
        }
        load()
    }

    fun setSection(section: MoneySection) {
        _state.update { it.copy(section = section, selectedCategory = null) }
    }

    fun setTransactionSort(sort: TransactionSort) {
        _state.update { it.copy(transactionSort = sort) }
    }

    /** Refresh the cache from the backend; dues load softly and never fail the
     * spend view, which is served from the local Room cache. */
    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val refresh = runCatching { repository.refresh(transactionDays = TRANSACTION_WINDOW_DAYS) }
            val dues = runCatching { repository.analysis() }.getOrNull()
            val recurring = runCatching { repository.recurringCharges() }.getOrNull()
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
                    recurringCharges = recurring ?: it.recurringCharges,
                )
            }
        }
    }

    /** Switch bucketing. Resets to the current period and clears any drill-down.
     * The category filter is intentionally kept because it's an orthogonal control. */
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

    fun toggleIncludeEmi() {
        _state.update {
            val include = !it.includeEmi
            it.copy(
                includeEmi = include,
                categoryFilter = if (include) {
                    it.categoryFilter
                } else {
                    it.categoryFilter - EMI_SPEND_CATEGORIES
                },
                selectedCategory = it.selectedCategory.takeIf { selected ->
                    include || selected == null || selected !in EMI_SPEND_CATEGORIES
                },
            )
        }
    }

    fun setBankUpiFilter(filter: String?) {
        _state.update { it.copy(bankUpiFilter = filter, selectedCategory = null) }
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

    fun beginRefund(debit: Transaction) {
        if (debit.direction != "debit") return
        _state.update {
            it.copy(refundDebit = debit, refundSaving = false, refundError = null)
        }
    }

    fun dismissRefund() {
        if (_state.value.refundSaving) return
        _state.update { it.copy(refundDebit = null, refundError = null) }
    }

    fun confirmRefund() {
        val debitId = _state.value.refundDebit?.id ?: return
        if (_state.value.refundSaving) return
        viewModelScope.launch {
            _state.update { it.copy(refundSaving = true, refundError = null) }
            val result = runCatching { repository.markAsRefunded(debitId) }
            _state.update {
                if (result.isSuccess) {
                    it.copy(
                        refundDebit = null,
                        refundSaving = false,
                        completedRefund = result.getOrThrow(),
                    )
                } else {
                    it.copy(
                        refundSaving = false,
                        refundError = result.exceptionOrNull()?.message
                            ?: "Couldn't mark this transaction as refunded",
                    )
                }
            }
        }
    }

    fun undoRefund(refundId: Int) {
        viewModelScope.launch {
            val result = runCatching { repository.undoRefund(refundId) }
            if (result.isFailure) {
                _state.update {
                    it.copy(error = result.exceptionOrNull()?.message ?: "Couldn't undo refund")
                }
            }
        }
    }

    fun consumeCompletedRefund() {
        _state.update { it.copy(completedRefund = null) }
    }

    fun beginCategoryEdit(transaction: Transaction) {
        _state.update {
            it.copy(categoryEditTransaction = transaction, categorySaving = false, categoryError = null)
        }
    }

    fun dismissCategoryEdit() {
        if (_state.value.categorySaving) return
        _state.update { it.copy(categoryEditTransaction = null, categoryError = null) }
    }

    fun saveCategory(category: String?) {
        val transactionId = _state.value.categoryEditTransaction?.id ?: return
        if (_state.value.categorySaving) return
        viewModelScope.launch {
            _state.update { it.copy(categorySaving = true, categoryError = null) }
            val result = runCatching { repository.updateTransactionCategory(transactionId, category) }
            _state.update {
                if (result.isSuccess) {
                    it.copy(categoryEditTransaction = null, categorySaving = false)
                } else {
                    it.copy(
                        categorySaving = false,
                        categoryError = result.exceptionOrNull()?.message ?: "Couldn't update category",
                    )
                }
            }
        }
    }

    fun beginBudgetEdit(category: String) {
        _state.update { it.copy(budgetEditCategory = category, budgetSaving = false, budgetError = null) }
    }

    fun dismissBudgetEdit() {
        if (_state.value.budgetSaving) return
        _state.update { it.copy(budgetEditCategory = null, budgetError = null) }
    }

    fun saveBudget(monthlyLimit: BigDecimal) {
        val category = _state.value.budgetEditCategory ?: return
        if (_state.value.budgetSaving) return
        viewModelScope.launch {
            _state.update { it.copy(budgetSaving = true, budgetError = null) }
            val result = runCatching { repository.setBudget(category, monthlyLimit) }
            _state.update {
                if (result.isSuccess) {
                    it.copy(budgetEditCategory = null, budgetSaving = false)
                } else {
                    it.copy(
                        budgetSaving = false,
                        budgetError = result.exceptionOrNull()?.message ?: "Couldn't save budget",
                    )
                }
            }
        }
    }

    fun removeBudget() {
        val category = _state.value.budgetEditCategory ?: return
        if (_state.value.budgetSaving) return
        viewModelScope.launch {
            _state.update { it.copy(budgetSaving = true, budgetError = null) }
            val result = runCatching { repository.deleteBudget(category) }
            _state.update {
                if (result.isSuccess) {
                    it.copy(budgetEditCategory = null, budgetSaving = false)
                } else {
                    it.copy(
                        budgetSaving = false,
                        budgetError = result.exceptionOrNull()?.message ?: "Couldn't remove budget",
                    )
                }
            }
        }
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
        const val STATEMENT_CACHE_MONTHS = 7
    }
}
