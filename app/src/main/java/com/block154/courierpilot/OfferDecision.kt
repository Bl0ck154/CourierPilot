package com.block154.courierpilot

/**
 * Five intentionally simple profitability bands.
 *
 * Human-readable verdict labels were removed from the live card: the emoji is the verdict and the
 * actual native-money/km number is shown next to it. UNKNOWN is used until Valhalla returns a usable route.
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
    val currencyCode: String? = null,
) {
    /** Native-money/km. Kept as an alias while legacy callers still use the old field name. */
    val moneyPerKilometer: Double? get() = euroPerKilometer
}

/**
 * Sorted native-money/km percentile edges for the five live bands. Equal percentile boundaries are
 * valid for small/flat samples; the live advisor passes null until market evidence is ready.
 */
internal data class OfferDecisionThresholds(
    val terribleBelow: Double,
    val badBelow: Double,
    val okAtMost: Double,
    val goodBelow: Double,
) {
    init {
        require(terribleBelow > 0.0)
        require(badBelow >= terribleBelow)
        require(okAtMost >= badBelow)
        require(goodBelow >= okAtMost)
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
        thresholds: OfferDecisionThresholds? = null,
    ): OfferDecision {
        val money = parsed.money ?: parsed.priceCents?.takeIf { it > 0 }?.let { MoneyAmount(it.toLong(), "EUR", 2) }
        val major = money?.major()?.toDouble()?.takeIf { it > 0.0 }
        val routeMeters = averageValhallaDistanceMeters(pedestrianRoute, cyclewayRoute)
        val routeKm = routeMeters?.div(1000.0)
        val perKm = if (major != null && routeKm != null && routeKm > 0.0) major / routeKm else null
        val band = if (thresholds == null) OfferDecisionBand.UNKNOWN else bandFor(perKm, thresholds)

        return OfferDecision(
            rating = band.rating,
            band = band,
            euroPerKilometer = perKm,
            routeDistanceMeters = routeMeters,
            routeVerifiedKilometerRate = routeMeters != null,
            currencyCode = money?.currencyCode,
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
        thresholds: OfferDecisionThresholds,
    ): OfferDecisionBand = when {
        perKm == null -> OfferDecisionBand.UNKNOWN
        perKm < thresholds.terribleBelow -> OfferDecisionBand.TERRIBLE
        perKm < thresholds.badBelow -> OfferDecisionBand.BAD
        perKm <= thresholds.okAtMost -> OfferDecisionBand.OK
        perKm < thresholds.goodBelow -> OfferDecisionBand.GOOD
        else -> OfferDecisionBand.FIRE
    }
}
