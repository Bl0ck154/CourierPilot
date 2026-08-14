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
        return OfferEconomicsEstimate(
            priceCents,
            route.distanceMeters,
            route.durationSeconds,
            restaurantWaitSeconds,
            handoffSeconds,
            euros / kilometers,
            euros * 3600.0 / totalSeconds,
            personalizedWaitApplied,
        )
    }
}
