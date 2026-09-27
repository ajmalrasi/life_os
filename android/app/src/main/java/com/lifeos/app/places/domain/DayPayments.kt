package com.lifeos.app.places.domain

import com.lifeos.app.domain.model.Transaction
import com.lifeos.app.places.data.PlaceSampleEntity
import com.lifeos.app.places.data.PlaceStopEntity
import com.lifeos.app.trips.location.StopDetection
import java.math.BigDecimal

/** A day's spending laid over its places. Totals follow Money's default view (spend only, EMI excluded). */
data class DayPayments(
    val byStop: Map<String, List<Transaction>>,
    /** Keyed by the [TimelineEntry.Move.from] of the move the payment happened during. */
    val byMove: Map<Long, List<Transaction>>,
    /** No exact time, or no location recorded near it. */
    val unplaced: List<Transaction>,
    val total: BigDecimal,
    /** Paid away from Home; null until Home is saved. */
    val whileOut: BigDecimal?,
) {
    companion object {
        val EMPTY = DayPayments(emptyMap(), emptyMap(), emptyList(), BigDecimal.ZERO, null)

        fun of(
            spend: List<Transaction>,
            stops: List<PlaceStopEntity>,
            samples: List<PlaceSampleEntity>,
            moves: List<TimelineEntry.Move>,
            home: Pair<Double, Double>?,
            now: Long,
        ): DayPayments {
            val byStop = mutableMapOf<String, MutableList<Transaction>>()
            val byMove = mutableMapOf<Long, MutableList<Transaction>>()
            val unplaced = mutableListOf<Transaction>()
            var whileOut = BigDecimal.ZERO
            fun away(lat: Double, lng: Double) = home != null &&
                StopDetection.distanceMeters(home.first, home.second, lat, lng) > DaySummary.AWAY_METERS
            spend.sortedBy { it.txnAt }.forEach { txn ->
                val paidAt = txn.txnAt?.toEpochMilli()
                when (val place = PaymentPlaceMatcher.match(paidAt, stops, samples, now)) {
                    is PaymentPlace.AtStop -> {
                        byStop.getOrPut(place.stop.id) { mutableListOf() } += txn
                        if (away(place.stop.latitude, place.stop.longitude)) whileOut += txn.amount
                    }
                    is PaymentPlace.OnTheMove -> {
                        val move = moves.firstOrNull { paidAt!! in it.from..it.to }
                        if (move != null) byMove.getOrPut(move.from) { mutableListOf() } += txn else unplaced += txn
                        if (away(place.sample.latitude, place.sample.longitude)) whileOut += txn.amount
                    }
                    PaymentPlace.Unknown, PaymentPlace.NoTime -> unplaced += txn
                }
            }
            return DayPayments(
                byStop = byStop,
                byMove = byMove,
                unplaced = unplaced,
                total = spend.fold(BigDecimal.ZERO) { sum, txn -> sum + txn.amount },
                whileOut = home?.let { whileOut },
            )
        }
    }
}
