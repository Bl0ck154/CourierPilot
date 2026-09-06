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

    @Test
    fun staleWoltUnconfirmedSurfaceGetsShortRecoveryWindow() {
        assertTrue(
            LiveAdvisorRestorePolicy.recoveryWindowMs(
                "Wolt",
                "Wolt offer surface remained unconfirmed",
            ) == 6_000L,
        )
    }

    @Test
    fun foregroundSwitchDoesNotExpireHiddenOfferByTimer() {
        assertTrue(
            LiveAdvisorRestorePolicy.recoveryWindowMs(
                "Wolt",
                "foreground changed to com.example.maps",
            ) == null,
        )
    }
    @Test
    fun singleTransientWoltHomeFrameDoesNotEndOffer() {
        val confirmation = WoltHomeEndConfirmation(graceMs = 650L, minChecks = 2)

        assertFalse(confirmation.observe(true, 1_000L))
        assertFalse(confirmation.observe(false, 1_300L))
        assertFalse(confirmation.observe(true, 2_000L))
    }

    @Test
    fun stableWoltHomeEndsOfferAfterShortConfirmation() {
        val confirmation = WoltHomeEndConfirmation(graceMs = 650L, minChecks = 2)

        assertFalse(confirmation.observe(true, 1_000L))
        assertTrue(confirmation.observe(true, 1_750L))
    }

}
