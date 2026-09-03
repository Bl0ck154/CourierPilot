package com.block154.courierpilot

/** Pure policy helpers kept outside Android UI code so flicker regressions are unit-testable. */
internal object LiveAdvisorCapturePolicy {
    fun shouldSuppressOverlay(platform: String): Boolean = !platform.equals("Wolt", ignoreCase = true)
}

internal class OfferDifferenceConfirmation(
    private val graceMs: Long = DEFAULT_GRACE_MS,
    private val minChecks: Int = DEFAULT_MIN_CHECKS,
) {
    private var sinceElapsedMs = 0L
    private var checks = 0

    fun observe(different: Boolean, nowElapsedMs: Long): Boolean {
        if (!different) {
            reset()
            return false
        }
        if (sinceElapsedMs == 0L) sinceElapsedMs = nowElapsedMs.coerceAtLeast(1L)
        checks += 1
        return checks >= minChecks && nowElapsedMs - sinceElapsedMs >= graceMs
    }

    fun reset() {
        sinceElapsedMs = 0L
        checks = 0
    }

    private companion object {
        const val DEFAULT_GRACE_MS = 1_500L
        const val DEFAULT_MIN_CHECKS = 3
    }
}
