package com.block154.courierpilot

/**
 * Owns the lightweight hot-poll schedule for Wolt price discovery.
 *
 * Accessibility parsing itself deliberately stays in OfferAccessibilityService. This class only
 * owns poll identity, throttle timing, drag deferral, reposting and cancellation so those rules can
 * be regression-tested without an AccessibilityService instance.
 */
internal class WoltPricePoller(
    private val pendingProvider: () -> PendingOffer?,
    private val overlayGestureActive: () -> Boolean,
    private val probePrice: () -> Boolean,
    private val nowElapsed: () -> Long,
    private val postNow: (Runnable) -> Unit,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val dragDeferMs: Long,
    private val hotPollMs: Long = DEFAULT_HOT_POLL_MS,
    private val eventThrottleMs: Long = DEFAULT_EVENT_THROTTLE_MS,
) {
    private var pollKey = ""
    private var lastProbeAtElapsed = 0L
    private val pollRunnable = Runnable { poll() }

    fun ensure(pending: PendingOffer, expedite: Boolean = false) {
        if (pending.packageName != CourierSignals.WOLT_PACKAGE) return
        val key = keyFor(pending)
        if (key != pollKey) {
            pollKey = key
            removeCallbacks(pollRunnable)
            postNow(pollRunnable)
            return
        }
        if (expedite) {
            removeCallbacks(pollRunnable)
            postNow(pollRunnable)
        }
    }

    /** Stop the current loop but preserve the cross-offer probe throttle timestamp. */
    fun cancel() {
        removeCallbacks(pollRunnable)
        pollKey = ""
    }

    private fun poll() {
        if (overlayGestureActive()) {
            postDelayed(pollRunnable, dragDeferMs)
            return
        }
        val pending = pendingProvider()
        if (pending == null || pending.packageName != CourierSignals.WOLT_PACKAGE) {
            pollKey = ""
            return
        }
        val key = keyFor(pending)
        if (pollKey != key) pollKey = key

        val now = nowElapsed()
        val sinceLast = now - lastProbeAtElapsed
        if (sinceLast >= 0L && sinceLast < eventThrottleMs) {
            postDelayed(pollRunnable, eventThrottleMs - sinceLast)
            return
        }
        lastProbeAtElapsed = now
        if (probePrice()) {
            pollKey = ""
            return
        }
        postDelayed(pollRunnable, hotPollMs)
    }

    private fun keyFor(pending: PendingOffer): String =
        "${pending.packageName}|${pending.armedAt}|${pending.notificationKey}"

    private companion object {
        const val DEFAULT_HOT_POLL_MS = 220L
        const val DEFAULT_EVENT_THROTTLE_MS = 90L
    }
}
