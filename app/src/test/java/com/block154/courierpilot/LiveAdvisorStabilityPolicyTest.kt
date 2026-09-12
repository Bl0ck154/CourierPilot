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
    fun activeWoltNotificationDefersScreenOnlyRouteConflictAtSamePrice() {
        val expected = ParsedOffer(
            priceCents = 783,
            distanceMeters = 6_800,
            restaurant = "Holy Donut",
            pickupAddresses = listOf("Vilniaus g. 18"),
            dropoffAddresses = listOf("Šatrijos gatvė 14"),
            deliveryCount = 1,
        )
        val noisyVisible = expected.copy(
            restaurant = "Ready in 10 min",
            pickupAddresses = listOf("Vilniaus g. 18", "Šatrijos gatvė 14"),
            dropoffAddresses = emptyList(),
            deliveryCount = 2,
        )

        assertTrue(
            LiveOfferReplacementPolicy.shouldDeferScreenReplacement(
                platform = "Wolt",
                hasActiveNotificationAnchor = true,
                expected = expected,
                visible = noisyVisible,
            )
        )
    }

    @Test
    fun explicitDifferentWoltPriceCanReplaceEvenWithActiveNotificationAnchor() {
        val expected = ParsedOffer(priceCents = 448, distanceMeters = 3_300, restaurant = "Holy Donut")
        val visible = ParsedOffer(priceCents = 783, distanceMeters = 6_800, restaurant = "Holy Donut")

        assertFalse(
            LiveOfferReplacementPolicy.shouldDeferScreenReplacement(
                platform = "Wolt",
                hasActiveNotificationAnchor = true,
                expected = expected,
                visible = visible,
            )
        )
    }

    @Test
    fun screenReplacementIsNotDeferredWithoutNotificationAnchorOrForBolt() {
        val expected = ParsedOffer(priceCents = 448, distanceMeters = 3_300, restaurant = "Holy Donut")
        val visible = ParsedOffer(priceCents = 448, distanceMeters = 3_300, restaurant = "Different")

        assertFalse(
            LiveOfferReplacementPolicy.shouldDeferScreenReplacement(
                platform = "Wolt",
                hasActiveNotificationAnchor = false,
                expected = expected,
                visible = visible,
            )
        )
        assertFalse(
            LiveOfferReplacementPolicy.shouldDeferScreenReplacement(
                platform = "Bolt",
                hasActiveNotificationAnchor = true,
                expected = expected,
                visible = visible,
            )
        )
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
