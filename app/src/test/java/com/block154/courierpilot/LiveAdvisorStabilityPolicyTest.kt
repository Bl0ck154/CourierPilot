package com.block154.courierpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAdvisorStabilityPolicyTest {
    @Test
    fun woltDisplayCaptureKeepsOverlayVisible() {
        assertFalse(LiveAdvisorCapturePolicy.shouldSuppressOverlay("Wolt"))
        assertFalse(LiveAdvisorCapturePolicy.shouldSuppressOverlay("wolt"))
        assertTrue(LiveAdvisorCapturePolicy.shouldSuppressOverlay("Bolt"))
    }

    @Test
    fun oneContradictoryComposeFrameCannotReplaceOffer() {
        val confirmation = OfferDifferenceConfirmation(graceMs = 1_500L, minChecks = 3)
        assertFalse(confirmation.observe(true, 1_000L))
        assertFalse(confirmation.observe(false, 1_200L))
        assertFalse(confirmation.observe(true, 2_000L))
        assertFalse(confirmation.observe(true, 2_900L))
    }

    @Test
    fun persistentDifferentOfferEventuallyConfirms() {
        val confirmation = OfferDifferenceConfirmation(graceMs = 1_500L, minChecks = 3)
        assertFalse(confirmation.observe(true, 1_000L))
        assertFalse(confirmation.observe(true, 1_800L))
        assertTrue(confirmation.observe(true, 2_500L))
    }
}
