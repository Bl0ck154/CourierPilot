package com.block154.courierpilot

/**
 * Five intentionally simple profitability bands.
 *
 * Human-readable verdict labels were removed from the live card: the emoji is the verdict and the
 * actual €/km number is shown next to it. UNKNOWN is used until Valhalla returns a usable route.
 */
internal enum class OfferDecisionBand(val emoji: String, val rating: Int?) {
    TERRIBLE("💩", 1),
    BAD("👎", 2),
    OK("😐", 3),
    GOOD("👍", 4),
    FIRE("🔥", 5),
    UNKNOWN("⚪", null),
}

internal data class OfferDecision(
    val rating: Int?,
    val band: OfferDecisionBand,
    val euroPerKilometer: Double?,
    val routeDistanceMeters: Int?,
    val routeVerifiedKilometerRate: Boolean,
)

/**
 * Sorted €/km edges for the five live bands. The defaults preserve the original fixed model;
 * MarketIntelligence can replace them with a city profile once the server has enough observations.
 */
internal data class OfferDecisionThresholds(
    val terribleBelow: Double,
    val badBelow: Double,
    val okAtMost: Double,
    val goodBelow: Double,
) {
    init {
        require(terribleBelow > 0.0)
        require(badBelow > terribleBelow)
        require(okAtMost > badBelow)
        require(goodBelow > okAtMost)
    }

    companion object {
        val DEFAULT = OfferDecisionThresholds(0.70, 0.85, 1.00, 1.25)
    }
}

/**
 * Live offer verdict based only on money per real Valhalla route kilometre.
 *
 * The courier app supplied distance and ETA are deliberately ignored for scoring. When both
 * pedestrian and cycleway Valhalla routes are available, their arithmetic mean is used: this keeps
 * a single odd routing profile from dominating the verdict. If only one Valhalla route succeeds,
 * that route is used as a graceful fallback. No Valhalla route means UNKNOWN, never a guessed score.
 */
internal object OfferDecisionEngine {
    fun evaluate(
        parsed: ParsedOffer,
        pedestrianRoute: RouteResult? = null,
        cyclewayRoute: RouteResult? = null,
        thresholds: OfferDecisionThresholds = OfferDecisionThresholds.DEFAULT,
    ): OfferDecision {
        val euros = parsed.priceCents?.takeIf { it > 0 }?.div(100.0)
        val routeMeters = averageValhallaDistanceMeters(pedestrianRoute, cyclewayRoute)
        val routeKm = routeMeters?.div(1000.0)
        val perKm = if (euros != null && routeKm != null && routeKm > 0.0) euros / routeKm else null
        val band = bandFor(perKm, thresholds)

        return OfferDecision(
            rating = band.rating,
            band = band,
            euroPerKilometer = perKm,
            routeDistanceMeters = routeMeters,
            routeVerifiedKilometerRate = routeMeters != null,
        )
    }

    internal fun averageValhallaDistanceMeters(
        pedestrianRoute: RouteResult?,
        cyclewayRoute: RouteResult?,
    ): Int? {
        val distances = listOfNotNull(
            pedestrianRoute?.distanceMeters?.takeIf { it > 0 },
            cyclewayRoute?.distanceMeters?.takeIf { it > 0 },
        )
        if (distances.isEmpty()) return null
        return (distances.sumOf { it.toLong() } / distances.size).toInt()
    }

    internal fun bandFor(
        perKm: Double?,
        thresholds: OfferDecisionThresholds = OfferDecisionThresholds.DEFAULT,
    ): OfferDecisionBand = when {
        perKm == null -> OfferDecisionBand.UNKNOWN
        perKm < thresholds.terribleBelow -> OfferDecisionBand.TERRIBLE
        perKm < thresholds.badBelow -> OfferDecisionBand.BAD
        perKm <= thresholds.okAtMost -> OfferDecisionBand.OK
        perKm < thresholds.goodBelow -> OfferDecisionBand.GOOD
        else -> OfferDecisionBand.FIRE
    }
}
