package com.lifeos.app

import com.lifeos.app.domain.model.Budget
import com.lifeos.app.domain.model.CardStatement
import com.lifeos.app.domain.model.MoneyProfile
import com.lifeos.app.domain.model.RecurringCharge
import com.lifeos.app.domain.model.Transaction
import com.lifeos.app.presentation.dashboard.OverviewTarget
import com.lifeos.app.presentation.dashboard.UpcomingKind
import com.lifeos.app.presentation.dashboard.attentionItems
import com.lifeos.app.presentation.dashboard.overviewMoney
import com.lifeos.app.presentation.dashboard.overviewTrip
import com.lifeos.app.presentation.dashboard.pastDueStatements
import com.lifeos.app.presentation.dashboard.pickOverviewTrip
import com.lifeos.app.presentation.dashboard.upcomingItems
import com.lifeos.app.trips.data.TripActivityEntity
import com.lifeos.app.trips.data.TripDayEntity
import com.lifeos.app.trips.data.TripDetail
import com.lifeos.app.trips.data.TripEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class TodayOverviewTest {

    private val today = LocalDate.of(2026, 9, 25)

    private fun txn(id: Int, date: LocalDate, amount: String, category: String?, direction: String = "debit") =
        Transaction(
            id = id, txnDate = date, txnAt = null, amount = BigDecimal(amount), currency = "INR",
            direction = direction, counterparty = null, category = null, spendCategory = category,
            account = null, source = "upi", reference = null,
        )

    private fun statement(
        id: Int,
        bank: String,
        label: String,
        due: LocalDate,
        total: String,
        statementDate: LocalDate = due.minusDays(18),
        paidAt: Instant? = null,
    ) = CardStatement(
        id = id, bank = bank, cardLabel = label, cardLast4 = label.takeLast(4), statementDate = statementDate,
        periodStart = null, periodEnd = null, dueDate = due, totalDue = BigDecimal(total), minDue = null,
        totalSpend = null, paidAt = paidAt, status = "upcoming", detailsStatus = "ready",
        detailsParserVersion = 2, detailsParsedAt = null,
    )

    private fun trip(id: String, start: String, end: String, status: String = "draft") =
        TripEntity(id = id, title = "Trip $id", startDate = start, endDate = end, timezone = "Asia/Kolkata", currency = "INR", status = status)

    @Test
    fun `month spend matches Money's default view with EMI excluded`() {
        val money = overviewMoney(
            transactions = listOf(
                txn(1, today, "1000", "food"),
                txn(2, today.minusDays(3), "500", "fuel"),
                txn(3, today.minusDays(4), "20000", "emi"),
                txn(4, today.minusDays(30), "999", "food"), // last month
                txn(5, today, "700", null), // transfer, excluded from spend
                txn(6, today, "300", "food", direction = "credit"),
            ),
            budgets = emptyList(),
            statements = emptyList(),
            cycles = emptyList(),
            profile = MoneyProfile(monthlyIncome = BigDecimal("100000"), savingsGoalPct = 50),
            today = today,
        )
        assertEquals(BigDecimal("1500"), money.monthSpend)
        assertEquals(BigDecimal("1000"), money.todaySpend)
        assertEquals(BigDecimal("21500"), money.monthOutflow)
        assertEquals(0.215, money.incomeShare!!, 1e-9)
    }

    @Test
    fun `over-budget and on-pace budgets need attention, over first by usage`() {
        val money = overviewMoney(
            transactions = listOf(txn(1, today, "9300", "fuel"), txn(2, today, "8800", "food")),
            budgets = listOf(Budget("food", BigDecimal("10000")), Budget("fuel", BigDecimal("6000"))),
            statements = emptyList(),
            cycles = emptyList(),
            profile = null,
            today = today,
        )
        assertEquals(listOf("fuel", "food"), money.budgets.map { it.category })
        val items = attentionItems(money, emptyList(), today)
        assertEquals("Fuel is ₹3,300 over budget", items[0].title)
        assertEquals("Food is on pace to exceed its budget", items[1].title)
        assertTrue(items.all { it.target == OverviewTarget.MONEY })
    }

    @Test
    fun `only a card's newest unpaid statement counts as past due`() {
        val statements = listOf(
            statement(1, "sbi", "SBI Card 1970", LocalDate.of(2026, 7, 14), "20636"),
            statement(2, "axis", "Axis 0872", LocalDate.of(2026, 8, 1), "3839"),
            // Axis's next bill supersedes the August one.
            statement(3, "axis", "Axis 0872", LocalDate.of(2026, 10, 2), "1200"),
            statement(4, "hdfc", "HDFC 6116", LocalDate.of(2026, 9, 1), "500", paidAt = Instant.EPOCH),
        )
        assertEquals(listOf(1), pastDueStatements(statements, today).map { it.id })
        val single = attentionItems(
            overviewMoney(emptyList(), emptyList(), statements, emptyList(), null, today),
            statements,
            today,
        ).single()
        assertEquals(1, single.statementId)
    }

    @Test
    fun `tracked trip wins, then one under way, then the soonest within two weeks`() {
        val soon = trip("soon", "2026-10-01", "2026-10-05")
        val later = trip("later", "2026-11-20", "2026-11-25")
        val past = trip("past", "2026-08-01", "2026-08-03")
        assertEquals("soon", pickOverviewTrip(listOf(later, soon, past), today)?.id)
        assertNull(pickOverviewTrip(listOf(later, past), today))
        val now = trip("now", "2026-09-24", "2026-09-27")
        assertEquals("now", pickOverviewTrip(listOf(soon, now), today)?.id)
        val tracked = trip("tracked", "2026-10-03", "2026-10-04", status = "active")
        assertEquals("tracked", pickOverviewTrip(listOf(soon, now, tracked), today)?.id)
    }

    @Test
    fun `trip under way shows its day, next activity and bills during it`() {
        val detail = TripDetail(
            trip = trip("d", "2026-09-24", "2026-09-27"),
            days = listOf(
                TripDayEntity("d1", "d", "2026-09-24", "Arrive", 0),
                TripDayEntity("d2", "d", "2026-09-25", "Old Delhi", 1),
            ),
            places = emptyList(),
            activities = listOf(
                TripActivityEntity(id = "a1", tripId = "d", dayId = "d2", title = "Red Fort", type = "visit", status = "completed", sortOrder = 0),
                TripActivityEntity(id = "a2", tripId = "d", dayId = "d2", title = "Chandni Chowk", type = "visit", sortOrder = 1),
            ),
            reminders = emptyList(),
            visits = emptyList(),
            expenses = emptyList(),
            attachments = emptyList(),
        )
        val statements = listOf(statement(1, "icici", "ICICI 0010", LocalDate.of(2026, 9, 26), "2500"))
        val overview = overviewTrip(detail, statements, today)!!
        assertEquals(2, overview.dayNumber)
        assertEquals(4, overview.totalDays)
        assertEquals("Old Delhi", overview.dayTitle)
        assertEquals("Chandni Chowk", overview.nextActivity)
        assertEquals(BigDecimal("2500"), overview.billsDuringTrip)
    }

    @Test
    fun `upcoming groups same-day bank bills and mixes trip and recurring by date`() {
        val detail = TripDetail(
            trip = trip("t", "2026-10-01", "2026-10-05"),
            days = listOf(TripDayEntity("t1", "t", "2026-10-01", "Arrival", 0)),
            places = emptyList(), activities = emptyList(), reminders = emptyList(),
            visits = emptyList(), expenses = emptyList(), attachments = emptyList(),
        )
        val statements = listOf(
            statement(1, "icici", "ICICI Coral", LocalDate.of(2026, 10, 4), "13800"),
            statement(2, "icici", "ICICI Amazon Credit Card", LocalDate.of(2026, 10, 4), "1381"),
            statement(3, "axis", "Axis  Flipkart  VISA", LocalDate.of(2026, 10, 5), "16554"),
            statement(4, "hdfc", "HDFC Diners", LocalDate.of(2026, 10, 30), "75031"), // beyond 14 days
        )
        val trip = overviewTrip(detail, statements, today)!!
        val recurring = listOf(
            RecurringCharge(
                counterparty = "SPOTIFY INDIA PVT LTD (spotify.bdsi@hdfcbank)", cadence = "monthly", occurrences = 4,
                medianAmount = BigDecimal("299"), lastAmount = BigDecimal("299"), lastDate = LocalDate.of(2026, 9, 1),
                nextExpectedDate = LocalDate.of(2026, 10, 1), priceChangePct = 0.0,
            ),
        )
        val items = upcomingItems(statements, trip, recurring, today)
        assertEquals(
            listOf(
                "Trip t starts",
                "Spotify India expected",
                "2 ICICI bills due",
                "Axis Flipkart VISA due",
                "Trip t ends",
            ),
            items.map { it.title },
        )
        val icici = items[2]
        assertEquals(UpcomingKind.BILL, icici.kind)
        assertEquals(BigDecimal("15181"), icici.amount)
        assertEquals("Coral, Amazon · trip day 4", icici.detail)
        assertNull(icici.statementId)
        assertEquals(3, items[3].statementId)
        assertEquals(BigDecimal("31735"), trip.billsDuringTrip)
    }
}
