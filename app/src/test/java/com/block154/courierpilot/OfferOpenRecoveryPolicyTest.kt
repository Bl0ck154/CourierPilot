package com.block154.courierpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferOpenRecoveryPolicyTest {
    @Test
    fun visibleCourierWindowIsNeverReopenedJustBecauseOfferVerificationIsLate() {
        assertFalse(
            OfferOpenRecoveryPolicy.shouldRetryExactPendingIntent(
                windowVisible = true,
                offerVisible = false,
            )
        )
    }

    @Test
    fun exactPendingIntentMayRetryWhenNothingOpened() {
        assertTrue(
            OfferOpenRecoveryPolicy.shouldRetryExactPendingIntent(
                windowVisible = false,
                offerVisible = false,
            )
        )
    }
}
