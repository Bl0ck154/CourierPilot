package com.block154.courierpilot

import kotlin.math.ceil
import kotlin.math.floor

internal data class LocalMarketProfile(
    val sampleCount: Int,
    val medianEurPerKm: Double,
    val thresholds: OfferDecisionThresholds,
    val source: String,
)

/**
 * Personal market model built from this device's own full-route offer history.
 *
 * A single unusually good/bad offer must not redefine the user's baseline. For six or more
 * observations we trim one value from each tail (then 5% per tail for larger histories) before
 * calculating percentile bands. The personal model becomes dominant as the local sample grows;
 * the server city profile remains a stabilizer/fallback rather than the primary source.
 */
internal object LocalMarketScoring {
    const val MIN_LOCAL_SAMPLES = 6

    fun profile(rates: List<Double>, source: String = "local_platform"): LocalMarketProfile? {
        val clean = rates.filter { it.isFinite() && it in 0.15..10.0 }.sorted()
        if (clean.size < MIN_LOCAL_SAMPLES) return null
        val robust = trimTails(clean)
        if (robust.size < 4) return null
        val edges = normalizedThresholds(
            quantile(robust, 0.15),
            quantile(robust, 0.35),
            quantile(robust, 0.65),
            quantile(robust, 0.85),
        )
        return LocalMarketProfile(
            sampleCount = clean.size,
            medianEurPerKm = quantile(robust, 0.50),
            thresholds = edges,
            source = source,
        )
    }

    fun combine(
        local: LocalMarketProfile?,
        city: OfferDecisionThresholds?,
    ): OfferDecisionThresholds {
        if (local == null) return city ?: OfferDecisionThresholds.DEFAULT
        val stabilizer = city ?: OfferDecisionThresholds.DEFAULT
        val localWeight = localWeight(local.sampleCount)
        return blend(local.thresholds, stabilizer, localWeight).normalized()
    }

    internal fun localWeight(sampleCount: Int): Double = when {
        sampleCount < MIN_LOCAL_SAMPLES -> 0.0
        sampleCount < 10 -> 0.55
        sampleCount < 20 -> 0.65
        sampleCount < 40 -> 0.75
        sampleCount < 80 -> 0.85
        else -> 0.90
    }

    private fun trimTails(sorted: List<Double>): List<Double> {
        if (sorted.size < MIN_LOCAL_SAMPLES) return sorted
        val trim = maxOf(1, floor(sorted.size * 0.05).toInt())
        if (sorted.size - trim * 2 < 4) return sorted
        return sorted.subList(trim, sorted.size - trim)
    }

    private fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.size == 1) return sorted.first()
        val position = (sorted.size - 1) * q.coerceIn(0.0, 1.0)
        val low = floor(position).toInt()
        val high = ceil(position).toInt()
        if (low == high) return sorted[low]
        val fraction = position - low
        return sorted[low] * (1.0 - fraction) + sorted[high] * fraction
    }

    private fun blend(
        local: OfferDecisionThresholds,
        fallback: OfferDecisionThresholds,
        localWeight: Double,
    ): OfferDecisionThresholds {
        val remoteWeight = 1.0 - localWeight
        return OfferDecisionThresholds(
            terribleBelow = local.terribleBelow * localWeight + fallback.terribleBelow * remoteWeight,
            badBelow = local.badBelow * localWeight + fallback.badBelow * remoteWeight,
            okAtMost = local.okAtMost * localWeight + fallback.okAtMost * remoteWeight,
            goodBelow = local.goodBelow * localWeight + fallback.goodBelow * remoteWeight,
        )
    }

    private fun normalizedThresholds(
        terribleBelow: Double,
        badBelow: Double,
        okAtMost: Double,
        goodBelow: Double,
    ): OfferDecisionThresholds {
        val first = terribleBelow.coerceAtLeast(0.20)
        val second = badBelow.coerceAtLeast(first + 0.03)
        val third = okAtMost.coerceAtLeast(second + 0.03)
        val fourth = goodBelow.coerceAtLeast(third + 0.03)
        return OfferDecisionThresholds(first, second, third, fourth)
    }

    private fun OfferDecisionThresholds.normalized(): OfferDecisionThresholds =
        normalizedThresholds(terribleBelow, badBelow, okAtMost, goodBelow)
}
