package com.lifeos.app.presentation.dashboard

import com.lifeos.app.domain.model.Budget
import com.lifeos.app.domain.model.CardStatement
import com.lifeos.app.domain.model.CurrentCardCycle
import com.lifeos.app.domain.model.MoneyProfile
import com.lifeos.app.domain.model.RecurringCharge
import com.lifeos.app.domain.model.TodayBrief
import com.lifeos.app.domain.model.Transaction
import com.lifeos.app.presentation.money.budgetPace
import com.lifeos.app.presentation.money.filterEmiTransactions
import com.lifeos.app.presentation.money.formatSpendAmount
import com.lifeos.app.presentation.money.prettyCategory
import com.lifeos.app.trips.data.TripDetail
import com.lifeos.app.trips.data.TripEntity
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Pure cross-area rollups for the Today page: a Money summary, the trip worth
 * showing, the next two weeks of dated items, and the few things that need the
 * user. Every number reuses the Money page's own rules (EMI excluded from
 * everyday spend, the same dues filter, the same budget pace), so Today never
 * disagrees with Money.
 */

/** How far ahead Today looks for bills, trip days and recurring charges. */
internal const val OVERVIEW_HORIZON_DAYS = 14L

data class OverviewMoney(
    /** Month-to-date everyday spend, EMI excluded (Money's default view). */
    val monthSpend: BigDecimal,
    val todaySpend: BigDecimal,
    /** Month-to-date outflow including EMI; the fair basis against income. */
    val monthOutflow: BigDecimal,
    val monthlyIncome: BigDecimal?,
    val savingsGoalPct: Int?,
    /** Generated card bills awaiting payment (not yet past due). */
    val billedTotal: BigDecimal,
    /** Recorded spend inside the open card cycles. */
    val openCycleTotal: BigDecimal,
    /** Budgets, most-used first. */
    val budgets: List<OverviewBudget>,
    val daysElapsed: Int,
    val daysInMonth: Int,
) {
    /** Share of income already spent (incl. EMI), or null without an income. */
    val incomeShare: Double?
        get() = monthlyIncome?.takeIf { it > BigDecimal.ZERO }
            ?.let { monthOutflow.toDouble() / it.toDouble() }

    /** Straight-line month-end share of income, like budget pace. */
    val projectedIncomeShare: Double?
        get() = incomeShare?.let { it / daysElapsed * daysInMonth }
}

/** One budget's month pace, from Money's own [budgetPace]. */
data class OverviewBudget(
    val category: String,
    val monthlyLimit: BigDecimal,
    val spentSoFar: BigDecimal,
    val projectedMonthTotal: BigDecimal,
    val onPaceToExceed: Boolean,
    val projectedExceedDay: Int?,
) {
    val usedShare: Double
        get() = if (monthlyLimit > BigDecimal.ZERO) spentSoFar.toDouble() / monthlyLimit.toDouble() else 0.0
}

/** Everything the Today page adds beyond health: Money, the trip, what's next. */
data class TodayOverview(
    val money: OverviewMoney,
    val trip: OverviewTrip?,
    val upcoming: List<UpcomingItem>,
    val attention: List<AttentionItem>,
)

data class OverviewTrip(
    val id: String,
    val title: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    /** 1-based trip day when the trip is under way, else null. */
    val dayNumber: Int?,
    val totalDays: Int,
    val daysUntilStart: Long,
    /** The title of today's day (under way) or day 1 (upcoming). */
    val dayTitle: String?,
    /** The next planned activity today, only while the trip is under way. */
    val nextActivity: String?,
    val trackingStatus: String?,
    /** Card bills that fall due between the trip's first and last day. */
    val billsDuringTrip: BigDecimal,
)

enum class OverviewTarget { MONEY, CARDS, TRIP, HEALTH }

data class AttentionItem(
    val title: String,
    val detail: String,
    val target: OverviewTarget,
    val statementId: Int? = null,
)

enum class UpcomingKind { BILL, TRIP, RECURRING }

data class UpcomingItem(
    val date: LocalDate,
    val kind: UpcomingKind,
    val title: String,
    val detail: String? = null,
    val amount: BigDecimal? = null,
    /** Set when the row is exactly one statement, so it can open its breakdown. */
    val statementId: Int? = null,
    val tripId: String? = null,
)

/** Everyday INR debits, as Money and the widget count them. */
private fun spendable(transactions: List<Transaction>): List<Transaction> =
    transactions.filter { it.direction == "debit" && it.spendCategory != null && it.currency == "INR" }

private fun List<Transaction>.sum(): BigDecimal =
    fold(BigDecimal.ZERO) { total, txn -> total + txn.amount }

/** Unpaid bills that are still due today or later, soonest first (Money's rule). */
internal fun activeDues(statements: List<CardStatement>, today: LocalDate): List<CardStatement> =
    statements
        .filter {
            it.paidAt == null &&
                !(it.dueDate?.isBefore(today) ?: false) &&
                (it.totalDue ?: BigDecimal.ZERO) > BigDecimal.ZERO
        }
        .sortedBy { it.dueDate ?: LocalDate.MAX }

/**
 * Unpaid bills whose due date has passed. Only each card's newest statement
 * counts: an older one is superseded by the next bill, so it would otherwise
 * nag forever about something already rolled forward.
 */
internal fun pastDueStatements(statements: List<CardStatement>, today: LocalDate): List<CardStatement> =
    statements
        .groupBy { it.bank to (it.cardLast4 ?: it.cardLabel) }
        .mapNotNull { (_, cardStatements) ->
            cardStatements.maxByOrNull { it.statementDate ?: it.dueDate ?: LocalDate.MIN }
        }
        .filter {
            it.paidAt == null &&
                (it.dueDate?.isBefore(today) ?: false) &&
                (it.totalDue ?: BigDecimal.ZERO) > BigDecimal.ZERO
        }
        .sortedBy { it.dueDate }

internal fun overviewMoney(
    transactions: List<Transaction>,
    budgets: List<Budget>,
    statements: List<CardStatement>,
    cycles: List<CurrentCardCycle>,
    profile: MoneyProfile?,
    today: LocalDate,
): OverviewMoney {
    val spend = spendable(transactions)
    val monthStart = today.withDayOfMonth(1)
    val thisMonth = spend.filter { !it.txnDate.isBefore(monthStart) && !it.txnDate.isAfter(today) }
    val everyday = filterEmiTransactions(thisMonth, includeEmi = false)
    val paces = budgets
        .map { budgetPace(it.category, it.monthlyLimit, spend, today) }
        .map {
            OverviewBudget(
                category = it.category,
                monthlyLimit = it.monthlyLimit,
                spentSoFar = it.spentSoFar,
                projectedMonthTotal = it.projectedMonthTotal,
                onPaceToExceed = it.onPaceToExceed,
                projectedExceedDay = it.projectedExceedDay,
            )
        }
        .sortedByDescending { it.usedShare }
    return OverviewMoney(
        monthSpend = everyday.sum(),
        todaySpend = filterEmiTransactions(spend, includeEmi = false).filter { it.txnDate == today }.sum(),
        monthOutflow = thisMonth.sum(),
        monthlyIncome = profile?.monthlyIncome,
        savingsGoalPct = profile?.savingsGoalPct,
        billedTotal = activeDues(statements, today).fold(BigDecimal.ZERO) { t, s -> t + (s.totalDue ?: BigDecimal.ZERO) },
        openCycleTotal = cycles.fold(BigDecimal.ZERO) { t, c -> t + c.netCycleAmount },
        budgets = paces,
        daysElapsed = today.dayOfMonth,
        daysInMonth = today.lengthOfMonth(),
    )
}

/**
 * The one trip Today shows: a trip being tracked, else one under way, else the
 * soonest trip starting within the horizon. Finished trips never show.
 */
internal fun pickOverviewTrip(trips: List<TripEntity>, today: LocalDate): TripEntity? {
    val candidates = trips.mapNotNull { trip ->
        val start = runCatching { LocalDate.parse(trip.startDate) }.getOrNull() ?: return@mapNotNull null
        val end = runCatching { LocalDate.parse(trip.endDate) }.getOrNull() ?: return@mapNotNull null
        Triple(trip, start, end)
    }.filter { (_, _, end) -> !end.isBefore(today) }
    candidates.firstOrNull { it.first.status == "active" || it.first.status == "paused" }?.let { return it.first }
    candidates.firstOrNull { (_, start, _) -> !start.isAfter(today) }?.let { return it.first }
    return candidates
        .filter { (_, start, _) -> ChronoUnit.DAYS.between(today, start) <= OVERVIEW_HORIZON_DAYS }
        .minByOrNull { it.second }
        ?.first
}

internal fun overviewTrip(detail: TripDetail, statements: List<CardStatement>, today: LocalDate): OverviewTrip? {
    val trip = detail.trip
    val start = runCatching { LocalDate.parse(trip.startDate) }.getOrNull() ?: return null
    val end = runCatching { LocalDate.parse(trip.endDate) }.getOrNull() ?: return null
    val underWay = !today.isBefore(start) && !today.isAfter(end)
    val days = detail.days.sortedBy { it.sortOrder }
    val focusDate = if (underWay) today else start
    val focusDay = days.firstOrNull { it.date == focusDate.toString() }
    val nextActivity = if (underWay && focusDay != null) {
        detail.activities
            .filter { it.dayId == focusDay.id && it.status == "planned" }
            .minByOrNull { it.sortOrder }
            ?.title
    } else {
        null
    }
    val billsDuringTrip = activeDues(statements, today)
        .filter { s -> s.dueDate?.let { !it.isBefore(start) && !it.isAfter(end) } ?: false }
        .fold(BigDecimal.ZERO) { t, s -> t + (s.totalDue ?: BigDecimal.ZERO) }
    return OverviewTrip(
        id = trip.id,
        title = trip.title,
        startDate = start,
        endDate = end,
        dayNumber = if (underWay) ChronoUnit.DAYS.between(start, today).toInt() + 1 else null,
        totalDays = ChronoUnit.DAYS.between(start, end).toInt() + 1,
        daysUntilStart = ChronoUnit.DAYS.between(today, start),
        dayTitle = focusDay?.title,
        nextActivity = nextActivity,
        trackingStatus = trip.status.takeIf { it == "active" || it == "paused" },
        billsDuringTrip = billsDuringTrip,
    )
}

/** Bills, trip start/end and expected recurring charges over the horizon, by date. */
internal fun upcomingItems(
    statements: List<CardStatement>,
    trip: OverviewTrip?,
    recurring: List<RecurringCharge>,
    today: LocalDate,
): List<UpcomingItem> {
    val horizonEnd = today.plusDays(OVERVIEW_HORIZON_DAYS)
    fun inWindow(date: LocalDate) = !date.isBefore(today) && !date.isAfter(horizonEnd)
    val items = mutableListOf<UpcomingItem>()

    activeDues(statements, today)
        .filter { it.dueDate != null && inWindow(it.dueDate) }
        .groupBy { it.dueDate!! to it.bank }
        .forEach { (key, bills) ->
            val (date, bank) = key
            val total = bills.fold(BigDecimal.ZERO) { t, s -> t + (s.totalDue ?: BigDecimal.ZERO) }
            val tripDay = trip?.takeIf { !date.isBefore(it.startDate) && !date.isAfter(it.endDate) }
                ?.let { "trip day ${ChronoUnit.DAYS.between(it.startDate, date) + 1}" }
            items += if (bills.size == 1) {
                UpcomingItem(
                    date = date,
                    kind = UpcomingKind.BILL,
                    title = "${bills.single().cardLabel.squeezeSpaces()} due",
                    detail = tripDay,
                    amount = total,
                    statementId = bills.single().id,
                )
            } else {
                UpcomingItem(
                    date = date,
                    kind = UpcomingKind.BILL,
                    title = "${bills.size} ${bank.uppercase()} bills due",
                    detail = listOfNotNull(
                        bills.joinToString(", ") { it.cardLabel.shortCardName(bank) },
                        tripDay,
                    ).joinToString(" · "),
                    amount = total,
                )
            }
        }

    if (trip != null) {
        if (trip.dayNumber == null && inWindow(trip.startDate)) {
            items += UpcomingItem(trip.startDate, UpcomingKind.TRIP, "${trip.title} starts", trip.dayTitle, tripId = trip.id)
        }
        if (inWindow(trip.endDate) && trip.endDate != today) {
            items += UpcomingItem(trip.endDate, UpcomingKind.TRIP, "${trip.title} ends", tripId = trip.id)
        }
    }

    recurring
        .filter { inWindow(it.nextExpectedDate) }
        .forEach {
            items += UpcomingItem(
                date = it.nextExpectedDate,
                kind = UpcomingKind.RECURRING,
                title = "${it.counterparty.recurringName()} expected",
                detail = it.cadence,
                amount = it.lastAmount,
            )
        }

    return items.sortedWith(compareBy<UpcomingItem> { it.date }.thenBy { it.kind.ordinal })
}

/**
 * The model's workout judgement, when it flags one. Whether workouts were real
 * exercise is judged by the model from each session's duration and effort, so
 * the app never applies its own threshold here.
 */
internal fun workoutAttention(brief: TodayBrief?): AttentionItem? {
    val note = brief?.workoutNote?.takeIf { brief.workoutAttention && it.isNotBlank() } ?: return null
    return AttentionItem(title = "Workouts need attention", detail = note, target = OverviewTarget.HEALTH)
}

/** A few items worth acting on now, most costly first. */
internal fun attentionItems(money: OverviewMoney, statements: List<CardStatement>, today: LocalDate): List<AttentionItem> {
    val items = mutableListOf<AttentionItem>()
    money.budgets.forEach { pace ->
        val over = pace.spentSoFar - pace.monthlyLimit
        if (over > BigDecimal.ZERO) {
            items += AttentionItem(
                title = "${prettyCategory(pace.category)} is ${formatOverviewAmount(over)} over budget",
                detail = "${formatOverviewAmount(pace.spentSoFar)} of ${formatOverviewAmount(pace.monthlyLimit)} · heading for ${formatOverviewAmount(pace.projectedMonthTotal)}",
                target = OverviewTarget.MONEY,
            )
        } else if (pace.onPaceToExceed) {
            items += AttentionItem(
                title = "${prettyCategory(pace.category)} is on pace to exceed its budget",
                detail = "${formatOverviewAmount(pace.spentSoFar)} of ${formatOverviewAmount(pace.monthlyLimit)}" +
                    (pace.projectedExceedDay?.let { " · crosses on day $it" } ?: ""),
                target = OverviewTarget.MONEY,
            )
        }
    }
    val pastDue = pastDueStatements(statements, today)
    if (pastDue.isNotEmpty()) {
        items += AttentionItem(
            title = if (pastDue.size == 1) "A card bill shows past due" else "${pastDue.size} card bills show past due",
            detail = pastDue.joinToString(" · ") {
                "${it.cardLabel.squeezeSpaces()} ${formatOverviewAmount(it.totalDue ?: BigDecimal.ZERO)}"
            } + ". Mark paid if settled.",
            target = OverviewTarget.CARDS,
            statementId = pastDue.singleOrNull()?.id,
        )
    }
    return items
}

private fun String.squeezeSpaces(): String = trim().replace(Regex("\\s+"), " ")

/** "HDFC Diners Privilege Credit Card" -> "Diners Privilege" when the bank is already named. */
private fun String.shortCardName(bank: String): String =
    squeezeSpaces().removePrefix(bank.uppercase()).removePrefix(bank.replaceFirstChar { it.uppercase() })
        .replace(Regex("\\s*Credit Card$", RegexOption.IGNORE_CASE), "")
        .trim()
        .ifEmpty { squeezeSpaces() }

/** "SPOTIFY INDIA PVT LTD (spotify.bdsi@hdfcbank)" -> "Spotify India". */
private fun String.recurringName(): String =
    substringBefore('(')
        .replace(Regex("\\b(PVT|PRIVATE|LTD|LIMITED|LLP|INC)\\b\\.?", RegexOption.IGNORE_CASE), "")
        .squeezeSpaces()
        .lowercase()
        .split(' ')
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        .ifEmpty { this }

internal fun formatOverviewAmount(amount: BigDecimal): String = formatSpendAmount(amount)
