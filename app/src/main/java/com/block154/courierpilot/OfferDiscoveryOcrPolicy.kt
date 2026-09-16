package com.block154.courierpilot

internal data class OfferDiscoveryOcrPlan(
    val runNow: Boolean,
    val retryAfterMs: Long? = null,
)

/**
 * Decides when passive screen discovery may spend a screenshot/OCR probe.
 *
 * Bolt's React Native/Mapbox offer sheet can change without emitting another useful Accessibility
 * event. Therefore an actually active Bolt window is allowed to keep probing at a low fixed cadence.
 * Event-driven discovery remains the default for every other courier window and for non-active Bolt
 * windows, so background/stale windows cannot trigger continuous screenshots.
 */
internal object OfferDiscoveryOcrPolicy {
    const val EVENT_WINDOW_MS = 1_500L
    const val OCR_MIN_INTERVAL_MS = 1_800L
    const val BOLT_ACTIVE_RESCAN_MS = 1_800L

    fun plan(
        packageName: String,
        activeCourierWindow: Boolean,
        nowElapsed: Long,
        lastCourierEventPackage: String,
        lastCourierEventAtElapsed: Long,
        lastDiscoveryOcrAtElapsed: Long,
    ): OfferDiscoveryOcrPlan {
        val recentCourierEvent =
            packageName == lastCourierEventPackage &&
                lastCourierEventAtElapsed > 0L &&
                nowElapsed - lastCourierEventAtElapsed in 0L..EVENT_WINDOW_MS
        val passiveActiveBolt =
            packageName == CourierSignals.BOLT_PACKAGE && activeCourierWindow

        if (!recentCourierEvent && !passiveActiveBolt) return OfferDiscoveryOcrPlan(runNow = false)

        if (lastDiscoveryOcrAtElapsed > 0L) {
            val sinceLastProbe = (nowElapsed - lastDiscoveryOcrAtElapsed).coerceAtLeast(0L)
            if (sinceLastProbe < OCR_MIN_INTERVAL_MS) {
                return OfferDiscoveryOcrPlan(
                    runNow = false,
                    retryAfterMs = OCR_MIN_INTERVAL_MS - sinceLastProbe,
                )
            }
        }
        return OfferDiscoveryOcrPlan(runNow = true)
    }

    fun passiveRescanDelayMs(packageName: String, activeCourierWindow: Boolean): Long? =
        BOLT_ACTIVE_RESCAN_MS.takeIf {
            packageName == CourierSignals.BOLT_PACKAGE && activeCourierWindow
        }
}
