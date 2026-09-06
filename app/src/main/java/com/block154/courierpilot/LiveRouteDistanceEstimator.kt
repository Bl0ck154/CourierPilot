package com.block154.courierpilot

internal data class LiveRouteDistanceEstimate(
    val distanceMeters: Int,
    val source: String,
    val sampleCount: Int,
)

/**
 * Zero-latency denominator while the real Valhalla route is still being prepared.
 *
 * The only safe immediate distance is the one the courier platform visibly supplied for this exact
 * offer. Historical route ratios are intentionally not applied here: bad/stale route samples can
 * otherwise corrupt the current €/km before the real route is available.
 */
internal object LiveRouteDistanceEstimator {
    fun estimate(platform: String, platformDistanceMeters: Int?): LiveRouteDistanceEstimate? {
        val platformMeters = platformDistanceMeters?.takeIf { it > 0 } ?: return null
        if (!platform.equals("Wolt", ignoreCase = true) && !platform.equals("Bolt", ignoreCase = true)) return null
        return LiveRouteDistanceEstimate(
            distanceMeters = platformMeters,
            source = "platform_distance",
            sampleCount = 0,
        )
    }
}
