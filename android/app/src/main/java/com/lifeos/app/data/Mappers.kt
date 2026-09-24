package com.lifeos.app.data

import com.lifeos.app.data.local.BudgetEntity
import com.lifeos.app.data.local.CardStatementEntity
import com.lifeos.app.data.local.CurrentCardCycleEntity
import com.lifeos.app.data.local.InsightEntity
import com.lifeos.app.data.local.MoodEntity
import com.lifeos.app.data.local.ScoreEntity
import com.lifeos.app.data.local.StateEntity
import com.lifeos.app.data.local.SynthesizedInsightEntity
import com.lifeos.app.data.local.TransactionEntity
import com.lifeos.app.data.remote.dto.CardStatementDto
import com.lifeos.app.data.remote.dto.CardStatementDetailsDto
import com.lifeos.app.data.remote.dto.CurrentCardCycleDto
import com.lifeos.app.data.remote.dto.CurrentCardCycleDetailsDto
import com.lifeos.app.data.remote.dto.CategorySpendDto
import com.lifeos.app.data.remote.dto.ChatMessageDto
import com.lifeos.app.data.remote.dto.DailyScoreDto
import com.lifeos.app.data.remote.dto.DailyStateDto
import com.lifeos.app.data.remote.dto.GithubDto
import com.lifeos.app.data.remote.dto.InsightDto
import com.lifeos.app.data.remote.dto.MonthTrendDto
import com.lifeos.app.data.remote.dto.MoodDto
import com.lifeos.app.data.remote.dto.NotificationDto
import com.lifeos.app.data.remote.dto.SourceStatusDto
import com.lifeos.app.data.remote.dto.BudgetPaceDto
import com.lifeos.app.data.remote.dto.MoneyProfileDto
import com.lifeos.app.data.remote.dto.RecurringChargeDto
import com.lifeos.app.data.remote.dto.RepaymentCandidateDto
import com.lifeos.app.data.remote.dto.RepaymentDto
import com.lifeos.app.data.remote.dto.RefundDto
import com.lifeos.app.data.remote.dto.SpendAnalysisDto
import com.lifeos.app.data.remote.dto.SpendTrendsDto
import com.lifeos.app.data.remote.dto.SynthesizedInsightDto
import com.lifeos.app.data.remote.dto.TransactionDto
import com.lifeos.app.domain.model.AppNotification
import com.lifeos.app.domain.model.SourceStatus
import com.lifeos.app.domain.model.Budget
import com.lifeos.app.domain.model.MoneyProfile
import com.lifeos.app.domain.model.RecurringCharge
import com.lifeos.app.domain.model.CardStatement
import com.lifeos.app.domain.model.CardStatementDetails
import com.lifeos.app.domain.model.CardStatementItem
import com.lifeos.app.domain.model.CurrentCardCycle
import com.lifeos.app.domain.model.CurrentCardCycleDetails
import com.lifeos.app.domain.model.StatementBreakdownItem
import com.lifeos.app.domain.model.CategorySpend
import com.lifeos.app.domain.model.ChatTurn
import com.lifeos.app.domain.model.DailyScore
import com.lifeos.app.domain.model.DailyState
import com.lifeos.app.domain.model.GithubActivity
import com.lifeos.app.domain.model.Insight
import com.lifeos.app.domain.model.MonthSpend
import com.lifeos.app.domain.model.MoodCheckin
import com.lifeos.app.domain.model.Repayment
import com.lifeos.app.domain.model.RepaymentCandidate
import com.lifeos.app.domain.model.Refund
import com.lifeos.app.domain.model.SpendAnalysis
import com.lifeos.app.domain.model.SpendTrends
import com.lifeos.app.domain.model.SynthesizedInsight
import com.lifeos.app.domain.model.Transaction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

// --- Mood ---
fun MoodCheckin.toDto() = MoodDto(day.toString(), mood, energy, focus, stress, note)
fun MoodCheckin.toEntity() = MoodEntity(day.toString(), mood, energy, focus, stress, note)
fun MoodEntity.toDomain() = MoodCheckin(LocalDate.parse(day), mood, energy, focus, stress, note)

// --- GitHub ---
fun GithubActivity.toDto() = GithubDto(
    day = day.toString(),
    commits = commits,
    additions = additions,
    deletions = deletions,
    activeRepos = activeRepos,
    codingMinutes = codingMinutes,
)

// --- Scores ---
fun DailyScoreDto.toEntity() = ScoreEntity(
    day, sleepScore, recoveryScore, executionScore, consistencyScore, burnoutRisk, wellnessScore, fitnessScore,
)
fun ScoreEntity.toDomain() = DailyScore(
    LocalDate.parse(day), sleepScore, recoveryScore, executionScore, consistencyScore, burnoutRisk, wellnessScore, fitnessScore,
)

// --- States ---
fun DailyStateDto.toEntity() = StateEntity(day, state, confidence, rationale)
fun StateEntity.toDomain() = DailyState(LocalDate.parse(day), state, confidence, rationale)

// --- Insights ---
fun InsightDto.toEntity() = InsightEntity(
    pair = "$driver|$outcome",
    driver = driver,
    outcome = outcome,
    correlation = correlation,
    sampleSize = sampleSize,
    lagDays = lagDays,
    summary = summary,
    narrative = narrative,
)
fun InsightEntity.toDomain() = Insight(driver, outcome, correlation, sampleSize, lagDays, summary, narrative)

// --- Synthesized insights ---
fun SynthesizedInsightDto.toEntity(rank: Int) = SynthesizedInsightEntity(
    rank = rank,
    title = title,
    body = body,
    theme = theme,
    action = action,
    severity = severity,
    cites = cites.joinToString(","),
)
fun SynthesizedInsightEntity.toDomain() = SynthesizedInsight(
    title = title,
    body = body,
    theme = theme,
    action = action,
    severity = severity,
    cites = cites.split(",").filter { it.isNotBlank() },
)

// --- Notifications ---
fun NotificationDto.toDomain() = AppNotification(severity, title, detail)

fun SourceStatusDto.toDomain() = SourceStatus(
    source = source,
    lastSuccessAt = lastSuccessAt?.let(Instant::parse),
    lastAttemptAt = lastAttemptAt?.let(Instant::parse),
    lastError = lastError,
    stale = stale,
)

// --- Budgets ---
fun BudgetPaceDto.toEntity() = BudgetEntity(category = category, monthlyLimit = monthlyLimit)

fun BudgetEntity.toDomain() = Budget(category = category, monthlyLimit = BigDecimal(monthlyLimit))

fun MoneyProfileDto.toDomain() = MoneyProfile(
    monthlyIncome = monthlyIncome?.let(::BigDecimal),
    savingsGoalPct = savingsGoalPct,
)

fun RecurringChargeDto.toDomain() = RecurringCharge(
    counterparty = counterparty,
    cadence = cadence,
    occurrences = occurrences,
    medianAmount = BigDecimal(medianAmount),
    lastAmount = BigDecimal(lastAmount),
    lastDate = LocalDate.parse(lastDate),
    nextExpectedDate = LocalDate.parse(nextExpectedDate),
    priceChangePct = priceChangePct,
)

// --- Chat ---
fun ChatTurn.toDto() = ChatMessageDto(role, content)

// --- Money: transactions ---
fun TransactionDto.toEntity() = TransactionEntity(
    id, txnDate, txnAt, amount, currency, direction, counterparty, category, spendCategory, account, source, reference,
)
fun TransactionEntity.toDomain() = Transaction(
    id = id,
    txnDate = LocalDate.parse(txnDate),
    txnAt = txnAt?.let { Instant.parse(it) },
    amount = BigDecimal(amount),
    currency = currency,
    direction = direction,
    counterparty = counterparty,
    category = category,
    spendCategory = spendCategory,
    account = account,
    source = source,
    reference = reference,
)
fun TransactionDto.toDomain() = toEntity().toDomain()
fun RepaymentCandidateDto.toDomain() = RepaymentCandidate(
    transaction = transaction.toDomain(),
    matchScore = matchScore,
    reason = reason,
)
fun RepaymentDto.toDomain() = Repayment(
    id = id,
    debit = debit.toDomain(),
    credit = credit.toDomain(),
)
fun RefundDto.toDomain() = Refund(
    id = id,
    debit = debit.toDomain(),
)

// --- Money: card statements ---
fun CardStatementDto.toEntity() = CardStatementEntity(
    id, bank, cardLabel, cardLast4, statementDate, periodStart, periodEnd, dueDate,
    totalDue, minDue, totalSpend, paidAt, status, detailsStatus, detailsParserVersion, detailsParsedAt,
)
fun CardStatementEntity.toDomain() = CardStatement(
    id = id,
    bank = bank,
    cardLabel = cardLabel,
    cardLast4 = cardLast4,
    statementDate = statementDate?.let { LocalDate.parse(it) },
    periodStart = periodStart?.let { LocalDate.parse(it) },
    periodEnd = periodEnd?.let { LocalDate.parse(it) },
    dueDate = dueDate?.let { LocalDate.parse(it) },
    totalDue = totalDue?.let { BigDecimal(it) },
    minDue = minDue?.let { BigDecimal(it) },
    totalSpend = totalSpend?.let { BigDecimal(it) },
    paidAt = paidAt?.let { Instant.parse(it) },
    status = status,
    detailsStatus = detailsStatus,
    detailsParserVersion = detailsParserVersion,
    detailsParsedAt = detailsParsedAt?.let { Instant.parse(it) },
)
fun CardStatementDetailsDto.toDomain() = CardStatementDetails(
    statement = statement.toEntity().toDomain(),
    items = items.map {
        CardStatementItem(
            id = it.id,
            sequence = it.sequence,
            txnDate = LocalDate.parse(it.txnDate),
            txnTime = it.txnTime?.let(LocalTime::parse),
            description = it.description,
            amount = BigDecimal(it.amount),
            direction = it.direction,
            itemType = it.itemType,
        )
    },
    breakdown = breakdown.map {
        StatementBreakdownItem(it.key, it.label, BigDecimal(it.total), it.count)
    },
)

fun CurrentCardCycleDto.toEntity() = CurrentCardCycleEntity(
    key = "${bank.lowercase()}:$cardLast4",
    bank = bank,
    cardLabel = cardLabel,
    cardLast4 = cardLast4,
    cycleStart = cycleStart,
    cycleEnd = cycleEnd,
    currentSpend = currentSpend,
    refunds = refunds,
    netCycleAmount = netCycleAmount,
    transactionCount = transactionCount,
    lastTransactionDate = lastTransactionDate,
    unconvertedTransactionCount = unconvertedTransactionCount,
)

fun CurrentCardCycleEntity.toDomain() = CurrentCardCycle(
    key = key,
    bank = bank,
    cardLabel = cardLabel,
    cardLast4 = cardLast4,
    cycleStart = LocalDate.parse(cycleStart),
    cycleEnd = LocalDate.parse(cycleEnd),
    currentSpend = BigDecimal(currentSpend),
    refunds = BigDecimal(refunds),
    netCycleAmount = BigDecimal(netCycleAmount),
    transactionCount = transactionCount,
    lastTransactionDate = lastTransactionDate?.let(LocalDate::parse),
    unconvertedTransactionCount = unconvertedTransactionCount,
)

fun CurrentCardCycleDetailsDto.toDomain() = CurrentCardCycleDetails(
    cycle = cycle.toEntity().toDomain(),
    transactions = transactions.map { it.toDomain() },
)

// --- Money: analysis / trends (computed server-side; mapped directly, not cached) ---
fun CategorySpendDto.toDomain() = CategorySpend(category, BigDecimal(total), count)

fun MonthTrendDto.toDomain() = MonthSpend(
    month = month,
    totalDebits = BigDecimal(totalDebits),
    totalCredits = BigDecimal(totalCredits),
    txnCount = txnCount,
    byCategory = byCategory.map { it.toDomain() },
)

fun SpendTrendsDto.toDomain() = SpendTrends(
    months = months.map { it.toDomain() },
    aggregateDebits = BigDecimal(aggregateDebits),
    aggregateByCategory = aggregateByCategory.map { it.toDomain() },
)

fun SpendAnalysisDto.toDomain() = SpendAnalysis(
    totalDueAllCards = BigDecimal(totalDueAllCards),
    minDueAllCards = BigDecimal(minDueAllCards),
    nextDueDate = nextDueDate?.let { LocalDate.parse(it) },
    cards = cards.map { it.toEntity().toDomain() },
    spendByCategory = spendByCategory.map { it.toDomain() },
    topMerchants = topMerchants.map { it.toDomain() },
)
