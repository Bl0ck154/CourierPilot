package com.block154.courierpilot

/**
 * Owns the mutable runtime state for one Accessibility capture flight.
 *
 * Android callback scheduling, watchdog Runnables, retry delays, OCR and persistence remain in
 * [OfferAccessibilityService]. This class only centralizes state that must move together when a
 * capture starts/finishes/times out, plus the per-offer screenshot failure counter.
 */
internal class OfferCaptureRuntime(timeoutMs: Long) {
    private val guard = CaptureFlightGuard(timeoutMs)
    private var busy = false
    private var screenshotFailureKey = ""
    private var screenshotFailureCount = 0

    val isBusy: Boolean
        get() = busy

    fun begin(nowElapsed: Long, operation: String, platform: String): Long {
        busy = true
        return guard.begin(nowElapsed, operation, platform)
    }

    fun isCurrent(token: Long): Boolean = busy && guard.isCurrent(token)

    fun finish(token: Long): Boolean {
        val finished = guard.finish(token)
        if (finished) busy = false
        return finished
    }

    fun recoverIfTimedOut(nowElapsed: Long): TimedOutCapture? {
        val timedOut = guard.recoverIfTimedOut(nowElapsed) ?: return null
        busy = false
        return timedOut
    }

    /**
     * Mirrors the service's historical `captureInFlight = false` paths without adding new token
     * invalidation semantics. A subsequent [begin] replaces the active guard token as before.
     */
    fun releaseBusy() {
        busy = false
    }

    fun cancel() {
        guard.cancel()
        busy = false
    }

    fun recordScreenshotFailure(key: String): Int {
        if (screenshotFailureKey != key) {
            screenshotFailureKey = key
            screenshotFailureCount = 0
        }
        screenshotFailureCount += 1
        return screenshotFailureCount
    }

    fun resetScreenshotFailures(key: String) {
        screenshotFailureKey = key
        screenshotFailureCount = 0
    }
}
