package com.lifeos.app.places

import com.lifeos.app.domain.model.Transaction
import com.lifeos.app.places.data.NameSource
import com.lifeos.app.places.data.PlaceSampleEntity
import com.lifeos.app.places.data.PlaceStopEntity
import com.lifeos.app.places.domain.DayPayments
import com.lifeos.app.places.domain.PaymentPlace
import com.lifeos.app.places.domain.PaymentPlaceMatcher
import com.lifeos.app.places.domain.TimelineEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class PaymentPlaceTest {
    private val home = 12.9716 to 77.5946
    private val minute = 60_000L
    private val base = 1_790_000_000_000L

    private fun stop(id: String, from: Int, to: Int?, latOffset: Double) = PlaceStopEntity(
        id, home.first + latOffset, home.second, id, NameSource.ADDRESS,
        base + from * minute, to?.let { base + it * minute },
    )

    private fun sample(at: Int, latOffset: Double) =
        PlaceSampleEntity(latitude = home.first + latOffset, longitude = home.second, accuracyMeters = 20f,
            speedMetersPerSecond = null, recordedAt = base + at * minute)

    private fun txn(id: Int, at: Int?, amount: String) = Transaction(
        id = id, txnDate = LocalDate.of(2026, 9, 21), txnAt = at?.let { Instant.ofEpochMilli(base + it * minute) },
        amount = BigDecimal(amount), currency = "INR", direction = "debit", counterparty = "Shop $id",
        category = null, spendCategory = "food", account = null, source = "upi", reference = null,
    )

    private val homeStop = stop("home", 0, 60, 0.0)
    private val cafe = stop("cafe", 90, 150, 0.05)

    @Test
    fun paymentDuringAStayIsAtThatStop() {
        val place = PaymentPlaceMatcher.match(base + 100 * minute, listOf(homeStop, cafe), emptyList(), base + 999 * minute)
        assertEquals(PaymentPlace.AtStop(cafe), place)
    }

    @Test
    fun paymentJustAfterLeavingStillCountsForTheStop() {
        val place = PaymentPlaceMatcher.match(base + 154 * minute, listOf(cafe), emptyList(), base + 999 * minute)
        assertEquals(PaymentPlace.AtStop(cafe), place)
    }

    @Test
    fun paymentBetweenStopsUsesTheNearestLocationInTime() {
        val near = sample(75, 0.02)
        val place = PaymentPlaceMatcher.match(base + 78 * minute, listOf(homeStop, cafe), listOf(sample(40, 0.0), near), base)
        assertEquals(PaymentPlace.OnTheMove(near), place)
    }

    @Test
    fun nothingNearbyOrNoTimeIsNeverGuessed() {
        assertEquals(PaymentPlace.Unknown, PaymentPlaceMatcher.match(base + 500 * minute, listOf(cafe), listOf(sample(100, 0.0)), base))
        assertEquals(PaymentPlace.NoTime, PaymentPlaceMatcher.match(null, listOf(cafe), emptyList(), base))
    }

    @Test
    fun dayPaymentsSplitsByPlaceAndCountsWhatWasSpentAwayFromHome() {
        val move = TimelineEntry.Move(base + 60 * minute, base + 90 * minute, 5_000.0, ongoing = false)
        val payments = DayPayments.of(
            spend = listOf(txn(1, 30, "100"), txn(2, 120, "450"), txn(3, 75, "60"), txn(4, null, "999")),
            stops = listOf(homeStop, cafe),
            samples = listOf(sample(74, 0.03)),
            moves = listOf(move),
            home = home,
            now = base + 999 * minute,
        )
        assertEquals(listOf(1), payments.byStop["home"]!!.map { it.id })
        assertEquals(listOf(2), payments.byStop["cafe"]!!.map { it.id })
        assertEquals(listOf(3), payments.byMove[move.from]!!.map { it.id })
        assertEquals(listOf(4), payments.unplaced.map { it.id })
        assertEquals(BigDecimal("1609"), payments.total)
        assertEquals(BigDecimal("510"), payments.whileOut)
    }

    @Test
    fun withoutHomeThereIsNoWhileOutTotal() {
        val payments = DayPayments.of(listOf(txn(1, 100, "10")), listOf(cafe), emptyList(), emptyList(), home = null, now = base)
        assertNull(payments.whileOut)
        assertTrue(payments.byStop.containsKey("cafe"))
    }
}
