package com.lifeos.app.places.domain

import com.lifeos.app.places.data.PlaceSampleEntity
import com.lifeos.app.places.data.PlaceStopEntity
import kotlin.math.abs

/** Where the user was when a payment was made. */
sealed interface PaymentPlace {
    data class AtStop(val stop: PlaceStopEntity) : PaymentPlace
    /** Between stops: the recorded location nearest in time. */
    data class OnTheMove(val sample: PlaceSampleEntity) : PaymentPlace
    /** Places recorded nothing close enough in time. */
    data object Unknown : PaymentPlace
    /** The bank alert gave only a date, so there is nothing to match. */
    data object NoTime : PaymentPlace
}

object PaymentPlaceMatcher {
    /** A payment this close to a stop's arrival or departure was made there. */
    const val STOP_MARGIN_MILLIS = 5 * 60 * 1000L
    /** Between stops, a location this close in time says where the payment was. */
    const val NEAREST_SAMPLE_MILLIS = 15 * 60 * 1000L

    /**
     * The stop whose stay (widened by [STOP_MARGIN_MILLIS]) contains [paidAt],
     * preferring the one with the nearest arrival when stays overlap; else the
     * nearest location within [NEAREST_SAMPLE_MILLIS]. Never a guess beyond that.
     */
    fun match(paidAt: Long?, stops: List<PlaceStopEntity>, samples: List<PlaceSampleEntity>, now: Long): PaymentPlace {
        if (paidAt == null) return PaymentPlace.NoTime
        stops
            .filter { paidAt >= it.arrivedAt - STOP_MARGIN_MILLIS && paidAt <= (it.departedAt ?: now) + STOP_MARGIN_MILLIS }
            .minByOrNull { abs(paidAt - it.arrivedAt) }
            ?.let { return PaymentPlace.AtStop(it) }
        return samples
            .filter { abs(it.recordedAt - paidAt) <= NEAREST_SAMPLE_MILLIS }
            .minByOrNull { abs(it.recordedAt - paidAt) }
            ?.let { PaymentPlace.OnTheMove(it) }
            ?: PaymentPlace.Unknown
    }
}
