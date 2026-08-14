package com.block154.courierpilot

internal data class OfferEconomicsEstimate(
    val priceCents: Int,
    val routeDistanceMeters: Int,
    val routeSeconds: Int,
    val restaurantWaitSeconds: Int,
    val handoffSeconds: Int,
    val euroPerKilometer: Double,
    val effectiveEuroPerHour: Double,
    val personalizedWaitApplied: Boolean,
)

/**
 * Transparent arithmetic for a future live advisor. It exposes the inputs/result and deliberately
 * does not convert them into GOOD/BAD or auto-accept decisions.
 */
internal object OfferEconomics {
    fun estimate(
        priceCents: Int,
        route: RouteResult,
        restaurantWaitSeconds: Int = 0,
        handoffSeconds: Int = 0,
        personalizedWaitApplied: Boolean = false,
    ): OfferEconomicsEstimate {
        require(priceCents > 0)
        require(route.distanceMeters > 0)
        require(route.durationSeconds > 0)
        require(restaurantWaitSeconds >= 0)
        require(handoffSeconds >= 0)

        val euros = priceCents / 100.0
        val kilometers = route.distanceMeters / 1000.0
        val totalSeconds = route.durationSeconds + restaurantWaitSeconds + handoffSeconds
        require(totalSeconds > 0)

        return OfferEconomicsEstimate(
            priceCents = priceCents,
            routeDistanceMeters = route.distanceMeters,
            routeSeconds = route.durationSeconds,
            restaurantWaitSeconds = restaurantWaitSeconds,
            handoffSeconds = handoffSeconds,
            euroPerKilometer = euros / kilometers,
            effectiveEuroPerHour = euros * 3600.0 / totalSeconds,
            personalizedWaitApplied = personalizedWaitApplied,
        )
    }
}
