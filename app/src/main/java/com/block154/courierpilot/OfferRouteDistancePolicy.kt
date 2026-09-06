package com.block154.courierpilot

import kotlin.math.roundToInt

/**
 * Historical route truth guard. A calculated full route is useful, but old geocoder failures can
 * leave a wildly wrong Valhalla distance persisted on an otherwise valid offer. When Wolt/Bolt also
 * supplied a visible platform distance, keep the calculated route only while it remains within a
 * deliberately generous bound. The platform value is always the fallback truth for history.
 */
internal object OfferRouteDistancePolicy {
    fun trustedCalculatedRouteMeters(platformDistanceMeters: Int?, calculatedRouteMeters: Int?): Int? {
        val calculated = calculatedRouteMeters?.takeIf { it > 0 } ?: return null
        val platform = platformDistanceMeters?.takeIf { it >= MIN_COMPARABLE_PLATFORM_METERS } ?: return calculated
        val allowance = maxOf(ABSOLUTE_ALLOWANCE_METERS, (platform * RELATIVE_ALLOWANCE).roundToInt())
        return calculated.takeIf { it <= platform + allowance }
    }

    fun effectiveMeters(platformDistanceMeters: Int?, calculatedRouteMeters: Int?): Int? =
        trustedCalculatedRouteMeters(platformDistanceMeters, calculatedRouteMeters)
            ?: platformDistanceMeters?.takeIf { it > 0 }

    fun calculatedRouteWasRejected(platformDistanceMeters: Int?, calculatedRouteMeters: Int?): Boolean =
        calculatedRouteMeters?.let { it > 0 } == true &&
            platformDistanceMeters?.let { it >= MIN_COMPARABLE_PLATFORM_METERS } == true &&
            trustedCalculatedRouteMeters(platformDistanceMeters, calculatedRouteMeters) == null

    const val MIN_COMPARABLE_PLATFORM_METERS = 500
    const val ABSOLUTE_ALLOWANCE_METERS = 1_500
    const val RELATIVE_ALLOWANCE = 0.85
}
