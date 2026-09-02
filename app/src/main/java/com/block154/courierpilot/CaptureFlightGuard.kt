package com.block154.courierpilot

internal data class TimedOutCapture(
    val token: Long,
    val operation: String,
    val platform: String,
    val ageMs: Long,
)

/**
 * Protects the Accessibility screenshot/OCR pipeline from Android callbacks that never return.
 *
 * Android 16/OEM builds can occasionally accept a screenshot request without ever invoking either
 * TakeScreenshotCallback method. Without a timeout, one such request leaves the whole capture
 * service permanently busy. Tokens invalidate late callbacks after recovery so they cannot take
 * ownership of a newer capture.
 */
internal class CaptureFlightGuard(private val timeoutMs: Long) {
    private var generation = 0L
    private var activeToken = 0L
    private var startedAtElapsed = 0L
    private var operation = ""
    private var platform = ""

    fun begin(nowElapsed: Long, operation: String, platform: String): Long {
        generation += 1L
        activeToken = generation
        startedAtElapsed = nowElapsed
        this.operation = operation
        this.platform = platform
        return activeToken
    }

    fun isCurrent(token: Long): Boolean = token != 0L && token == activeToken

    fun finish(token: Long): Boolean {
        if (!isCurrent(token)) return false
        clearActive()
        return true
    }

    fun recoverIfTimedOut(nowElapsed: Long): TimedOutCapture? {
        if (activeToken == 0L || startedAtElapsed <= 0L) return null
        val age = (nowElapsed - startedAtElapsed).coerceAtLeast(0L)
        if (age < timeoutMs) return null

        val timedOut = TimedOutCapture(activeToken, operation, platform, age)
        // Invalidate the timed-out token before a new capture is allowed to start.
        generation += 1L
        clearActive()
        return timedOut
    }

    private fun clearActive() {
        activeToken = 0L
        startedAtElapsed = 0L
        operation = ""
        platform = ""
    }
}
