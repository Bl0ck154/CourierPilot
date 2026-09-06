package com.block154.courierpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferHistoryResumePolicyTest {
    private val capturedAt = 1_000_000L

    private fun historical() = OfferRecord(
        id = 858,
        capturedAt = capturedAt,
        platform = "Wolt",
        packageName = CourierSignals.WOLT_PACKAGE,
        priceCents = 405,
        distanceMeters = 4_900,
        restaurant = "Georgian House",
        screenshotUri = "",
        screenshotFilename = "",
        rawText = "",
        merchantNames = listOf("Georgian House"),
        pickupAddresses = listOf("Mėsinių g. 4-3, Vilnius, 01130"),
        dropoffAddresses = listOf("Rasų gatvė 89, Vilnius, 11351"),
        deliveryCount = 1,
    )

    @Test
    fun sameVisibleOfferCanRecoverAfterAccessibilityFalseEnd() {
        val visible = ParsedOffer(
            priceCents = 405,
            distanceMeters = 4_900,
            restaurant = "Georgian House",
            merchantNames = listOf("Georgian House"),
            pickupAddresses = listOf("Mėsinių gatvė 4, LT-01130 Vilnius"),
            dropoffAddresses = listOf("Rasų g. 89, LT-11351 Vilnius"),
            deliveryCount = 1,
        )
        assertTrue(
            OfferHistoryResumePolicy.isStrongMatch(
                historical(),
                CourierSignals.WOLT_PACKAGE,
                visible,
                now = capturedAt + 45_000L,
            ),
        )
    }

    @Test
    fun sameVenueButDifferentDistanceCannotRecoverOldOffer() {
        val visible = ParsedOffer(
            priceCents = 405,
            distanceMeters = 3_200,
            restaurant = "Georgian House",
            merchantNames = listOf("Georgian House"),
        )
        assertFalse(
            OfferHistoryResumePolicy.isStrongMatch(
                historical(),
                CourierSignals.WOLT_PACKAGE,
                visible,
                now = capturedAt + 45_000L,
            ),
        )
    }

    @Test
    fun sameDistanceAndVenueButDifferentPriceCannotRecoverOldOffer() {
        val visible = ParsedOffer(
            priceCents = 525,
            distanceMeters = 4_900,
            restaurant = "Georgian House",
            merchantNames = listOf("Georgian House"),
        )
        assertFalse(
            OfferHistoryResumePolicy.isStrongMatch(
                historical(),
                CourierSignals.WOLT_PACKAGE,
                visible,
                now = capturedAt + 45_000L,
            ),
        )
    }

    @Test
    fun contradictoryDropoffCannotRecoverOldOffer() {
        val visible = ParsedOffer(
            priceCents = 405,
            distanceMeters = 4_900,
            restaurant = "Georgian House",
            merchantNames = listOf("Georgian House"),
            dropoffAddresses = listOf("Kalvarijų g. 88, Vilnius"),
        )
        assertFalse(
            OfferHistoryResumePolicy.isStrongMatch(
                historical(),
                CourierSignals.WOLT_PACKAGE,
                visible,
                now = capturedAt + 45_000L,
            ),
        )
    }

    @Test
    fun staleHistoryCannotResurrectOffer() {
        val visible = ParsedOffer(
            priceCents = 405,
            distanceMeters = 4_900,
            restaurant = "Georgian House",
            merchantNames = listOf("Georgian House"),
        )
        assertFalse(
            OfferHistoryResumePolicy.isStrongMatch(
                historical(),
                CourierSignals.WOLT_PACKAGE,
                visible,
                now = capturedAt + OfferHistoryResumePolicy.RECOVERY_WINDOW_MS + 1L,
            ),
        )
    }
}
