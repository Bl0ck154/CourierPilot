package com.block154.courierpilot

/**
 * An exact courier notification PendingIntent is enough once it has produced a courier window.
 * Re-sending one-shot PendingIntents can fail or navigate away from the live offer, so retries are
 * reserved for the case where no courier window appeared at all.
 */
internal object OfferOpenRecoveryPolicy {
    fun shouldRetryExactPendingIntent(windowVisible: Boolean, offerVisible: Boolean): Boolean =
        !offerVisible && !windowVisible
}
