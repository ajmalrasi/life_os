package com.lifeos.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.domain.model.Budget
import com.lifeos.app.domain.repository.MoneyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

data class MoneySettingsUiState(
    val incomeText: String = "",
    val savingsGoalText: String = "",
    val profileLoading: Boolean = true,
    val profileError: String? = null,
    val profileSaved: Boolean = false,
    val savingProfile: Boolean = false,
    val budgetEditCategory: String? = null,
    val budgetSaving: Boolean = false,
    val budgetError: String? = null,
)

@HiltViewModel
class MoneySettingsViewModel @Inject constructor(
    private val moneyRepository: MoneyRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MoneySettingsUiState())
    val state: StateFlow<MoneySettingsUiState> = _state

    val budgets: StateFlow<List<Budget>> = moneyRepository.observeBudgets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            runCatching { moneyRepository.moneyProfile() }
                .onSuccess { profile ->
                    _state.value = _state.value.copy(
                        incomeText = profile.monthlyIncome?.stripTrailingZeros()?.toPlainString() ?: "",
                        savingsGoalText = profile.savingsGoalPct?.toString() ?: "",
                        profileLoading = false,
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        profileLoading = false,
                        profileError = "Couldn't load your income. Check your connection.",
                    )
                }
        }
    }

    fun onIncomeChange(text: String) {
        _state.value = _state.value.copy(incomeText = text.filter { it.isDigit() || it == '.' }, profileSaved = false)
    }

    fun onSavingsGoalChange(text: String) {
        _state.value = _state.value.copy(savingsGoalText = text.filter { it.isDigit() }, profileSaved = false)
    }

    fun saveProfile() {
        val income = _state.value.incomeText.trim().toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
        val goal = _state.value.savingsGoalText.trim().toIntOrNull()?.coerceIn(0, 90)
        viewModelScope.launch {
            _state.value = _state.value.copy(savingProfile = true, profileError = null)
            runCatching { moneyRepository.setMoneyProfile(income, goal) }
                .onSuccess { _state.value = _state.value.copy(savingProfile = false, profileSaved = true) }
                .onFailure {
                    _state.value = _state.value.copy(
                        savingProfile = false,
                        profileError = "Couldn't save. Check your connection and try again.",
                    )
                }
        }
    }

    fun beginBudgetEdit(category: String) {
        _state.value = _state.value.copy(budgetEditCategory = category, budgetError = null)
    }

    fun dismissBudgetEdit() {
        _state.value = _state.value.copy(budgetEditCategory = null, budgetError = null)
    }

    fun saveBudget(amount: BigDecimal) {
        val category = _state.value.budgetEditCategory ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(budgetSaving = true, budgetError = null)
            runCatching { moneyRepository.setBudget(category, amount) }
                .onSuccess {
                    _state.value = _state.value.copy(budgetSaving = false, budgetEditCategory = null)
                }
                .onFailure {
                    _state.value = _state.value.copy(budgetSaving = false, budgetError = "Couldn't save the budget.")
                }
        }
    }

    fun removeBudget() {
        val category = _state.value.budgetEditCategory ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(budgetSaving = true, budgetError = null)
            runCatching { moneyRepository.deleteBudget(category) }
                .onSuccess {
                    _state.value = _state.value.copy(budgetSaving = false, budgetEditCategory = null)
                }
                .onFailure {
                    _state.value = _state.value.copy(budgetSaving = false, budgetError = "Couldn't remove the budget.")
                }
        }
    }
}
