package com.block154.courierpilot

import kotlin.math.roundToInt

internal enum class OfferDecisionBand(val emoji: String, val label: String) {
    TAKE("🔥", "БРАТИ"),
    GOOD("✅", "ДОБРЕ"),
    OK("🟡", "НОРМ"),
    WEAK("🟠", "СЛАБКЕ"),
    SKIP("🔴", "НЕ ВАРТО"),
    UNKNOWN("⚪", "НЕМАЄ ДАНИХ"),
}

internal data class OfferDecision(
    val score: Int?,
    val band: OfferDecisionBand,
    val euroPerKilometer: Double?,
    val conservativeEuroPerHour: Double?,
    val routeVerifiedKilometerRate: Boolean,
)

/**
 * Lightweight on-screen recommendation. It never accepts/rejects an order.
 *
 * The score intentionally uses the conservative (lower) end of the platform €/h estimate and
 * combines it with €/km. When Valhalla has a complete cycleway route, only the €/km component is
 * replaced by the route-derived value; platform ETA remains the time signal because it includes
 * pickup/handoff effects that raw routing duration does not.
 */
internal object OfferDecisionEngine {
    fun evaluate(
        parsed: ParsedOffer,
        completeCyclewayRoute: RouteResult? = null,
    ): OfferDecision {
        val euros = parsed.priceCents?.takeIf { it > 0 }?.div(100.0)
        val platformKm = parsed.distanceMeters?.takeIf { it > 0 }?.div(1000.0)
        val routeKm = completeCyclewayRoute?.distanceMeters?.takeIf { it > 0 }?.div(1000.0)
        val usedKm = routeKm ?: platformKm
        val perKm = if (euros != null && usedKm != null) euros / usedKm else null

        val conservativePerHour = parsed.estimatedMinutesMax
            ?.takeIf { it > 0 }
            ?.let { maxMinutes -> euros?.times(60.0)?.div(maxMinutes) }

        val kmScore = perKm?.let(::scorePerKm)
        val hourScore = conservativePerHour?.let(::scorePerHour)
        val score = when {
            kmScore != null && hourScore != null -> (kmScore * 0.45 + hourScore * 0.55).roundToInt()
            kmScore != null -> kmScore.roundToInt()
            hourScore != null -> hourScore.roundToInt()
            else -> null
        }?.coerceIn(0, 100)

        val band = when {
            score == null -> OfferDecisionBand.UNKNOWN
            score >= 80 -> OfferDecisionBand.TAKE
            score >= 65 -> OfferDecisionBand.GOOD
            score >= 50 -> OfferDecisionBand.OK
            score >= 35 -> OfferDecisionBand.WEAK
            else -> OfferDecisionBand.SKIP
        }

        return OfferDecision(
            score = score,
            band = band,
            euroPerKilometer = perKm,
            conservativeEuroPerHour = conservativePerHour,
            routeVerifiedKilometerRate = routeKm != null,
        )
    }

    private fun scorePerKm(value: Double): Double = interpolate(
        value,
        listOf(
            0.60 to 0.0,
            0.80 to 30.0,
            1.00 to 55.0,
            1.20 to 75.0,
            1.50 to 100.0,
        ),
    )

    private fun scorePerHour(value: Double): Double = interpolate(
        value,
        listOf(
            8.0 to 0.0,
            10.0 to 25.0,
            12.0 to 50.0,
            15.0 to 75.0,
            18.0 to 100.0,
        ),
    )

    private fun interpolate(value: Double, anchors: List<Pair<Double, Double>>): Double {
        if (value <= anchors.first().first) return anchors.first().second
        if (value >= anchors.last().first) return anchors.last().second
        for (index in 0 until anchors.lastIndex) {
            val (x1, y1) = anchors[index]
            val (x2, y2) = anchors[index + 1]
            if (value in x1..x2) {
                val t = (value - x1) / (x2 - x1)
                return y1 + (y2 - y1) * t
            }
        }
        return 0.0
    }
}
