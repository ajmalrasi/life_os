package com.lifeos.app.data.repository

import com.lifeos.app.data.local.BudgetDao
import com.lifeos.app.data.local.CardStatementDao
import com.lifeos.app.data.local.CurrentCardCycleDao
import com.lifeos.app.data.local.TransactionDao
import com.lifeos.app.data.remote.dto.BudgetInDto
import com.lifeos.app.data.remote.LifeOsApi
import com.lifeos.app.data.remote.dto.RepaymentCreateDto
import com.lifeos.app.data.remote.dto.RefundCreateDto
import com.lifeos.app.data.remote.dto.StatementPaidUpdateDto
import com.lifeos.app.data.remote.dto.TransactionCategoryUpdateDto
import com.lifeos.app.data.toDomain
import com.lifeos.app.data.toEntity
import com.lifeos.app.domain.model.Budget
import com.lifeos.app.domain.model.CardStatement
import com.lifeos.app.domain.model.CardStatementDetails
import com.lifeos.app.domain.model.CurrentCardCycle
import com.lifeos.app.domain.model.CurrentCardCycleDetails
import com.lifeos.app.domain.model.SpendAnalysis
import com.lifeos.app.domain.model.SpendTrends
import com.lifeos.app.domain.model.Transaction
import com.lifeos.app.domain.model.Repayment
import com.lifeos.app.domain.model.RepaymentCandidate
import com.lifeos.app.domain.model.Refund
import com.lifeos.app.domain.repository.MoneyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject

class MoneyRepositoryImpl @Inject constructor(
    private val api: LifeOsApi,
    private val transactionDao: TransactionDao,
    private val cardStatementDao: CardStatementDao,
    private val currentCardCycleDao: CurrentCardCycleDao,
    private val budgetDao: BudgetDao,
) : MoneyRepository {

    override fun observeBudgets(): Flow<List<Budget>> =
        budgetDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun setBudget(category: String, monthlyLimit: java.math.BigDecimal): Budget {
        val result = api.setBudget(category, BudgetInDto(monthlyLimit.toPlainString()))
        budgetDao.upsertAll(listOf(result.toEntity()))
        return Budget(category = result.category, monthlyLimit = java.math.BigDecimal(result.monthlyLimit))
    }

    override suspend fun deleteBudget(category: String) {
        api.deleteBudget(category)
        budgetDao.deleteByCategory(category)
    }

    override fun observeTransactions(days: Int): Flow<List<Transaction>> {
        val since = LocalDate.now().minusDays(days.toLong()).toString()
        return transactionDao.observeSince(since).map { list -> list.map { it.toDomain() } }
    }

    override fun observeStatements(months: Int): Flow<List<CardStatement>> =
        cardStatementDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeCurrentCardCycles(): Flow<List<CurrentCardCycle>> =
        currentCardCycleDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun currentCardCycleDetails(
        bank: String,
        cardLast4: String,
    ): CurrentCardCycleDetails = api.currentCardCycleDetails(bank, cardLast4).toDomain()

    override suspend fun refresh(transactionDays: Int, statementMonths: Int) {
        val since = LocalDate.now().minusDays(transactionDays.toLong()).toString()
        val fresh = api.listTransactions(transactionDays)
        val freshCycles = runCatching { api.currentCardCycles() }.getOrNull()
        // Reconcile, don't just append: a transaction the server deleted
        // (e.g. reprocessed under a new id) must disappear locally too, not
        // linger forever as a phantom duplicate alongside its replacement.
        if (fresh.isEmpty()) {
            transactionDao.deleteSince(since)
        } else {
            transactionDao.deleteStaleSince(since, fresh.map { it.id })
            transactionDao.upsertAll(fresh.map { it.toEntity() })
        }
        // Mirror the server instead of appending forever. Statements can be
        // deliberately removed server-side and must not survive as stale cache.
        cardStatementDao.replaceAll(api.listStatements(statementMonths).map { it.toEntity() })
        // Keep the previous cache during a rolling backend/app deployment where
        // the new endpoint may briefly be unavailable. A successful empty
        // response still clears stale cycles through replaceAll.
        freshCycles?.let { response ->
            currentCardCycleDao.replaceAll(response.cycles.map { it.toEntity() })
        }
        // Best-effort: budgets are a small, optional feature; never fail the
        // whole money refresh (statements/transactions) over them.
        runCatching { budgetDao.replaceAll(api.budgets().map { it.toEntity() }) }
    }

    override suspend fun analysis(days: Int): SpendAnalysis = api.spendAnalysis(days).toDomain()

    override suspend fun trends(months: Int): SpendTrends = api.spendTrends(months).toDomain()

    override suspend fun syncNow() {
        api.syncExpenses()
        api.syncStatements()
        refresh()
    }

    override suspend fun repaymentCandidates(debitId: Int): List<RepaymentCandidate> =
        api.repaymentCandidates(debitId).map { it.toDomain() }

    override suspend fun markAsRepaid(debitId: Int, creditId: Int): Repayment =
        api.markAsRepaid(RepaymentCreateDto(debitId, creditId)).toDomain()

    override suspend fun undoRepayment(repaymentId: Int): Repayment =
        api.undoRepayment(repaymentId).toDomain()

    override suspend fun markAsRefunded(debitId: Int): Refund {
        val response = api.markAsRefunded(RefundCreateDto(debitId))
        transactionDao.upsertAll(listOf(response.debit.toEntity()))
        return response.toDomain()
    }

    override suspend fun undoRefund(refundId: Int): Refund {
        val response = api.undoRefund(refundId)
        transactionDao.upsertAll(listOf(response.debit.toEntity()))
        return response.toDomain()
    }

    override suspend fun updateTransactionCategory(
        transactionId: Int,
        category: String?,
    ): Transaction {
        val updated = api.updateTransactionCategory(
            transactionId, TransactionCategoryUpdateDto(category)
        )
        transactionDao.upsertAll(listOf(updated.toEntity()))
        return updated.toDomain()
    }

    override suspend fun updateStatementPaid(statementId: Int, paid: Boolean): CardStatement {
        val updated = api.updateStatementPaid(statementId, StatementPaidUpdateDto(paid))
        cardStatementDao.upsertAll(listOf(updated.toEntity()))
        return updated.toEntity().toDomain()
    }

    override suspend fun statementDetails(statementId: Int): CardStatementDetails =
        api.statementDetails(statementId).toDomain()
}
