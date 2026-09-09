package com.block154.courierpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOfferUserDismissalPolicyTest {
    @Test
    fun merchantEnrichmentDoesNotResurrectSameWoltOffer() {
        val base = ParsedOffer(
            priceCents = 370,
            distanceMeters = 4_300,
            restaurant = null,
            pickupAddresses = listOf("Vokiečių g. 9, Vilnius"),
            dropoffAddresses = listOf("Manufaktūrų gatvė 29, Vilnius"),
            deliveryCount = 1,
        )
        val enriched = base.copy(
            restaurant = "Holy Donut (Vokiečių g.)",
            merchantNames = listOf("Holy Donut (Vokiečių g.)"),
        )

        assertTrue(
            LiveOfferUserDismissalPolicy.isSameOffer(
                LiveOfferUserDismissalPolicy.identity(CourierSignals.WOLT_PACKAGE, base),
                LiveOfferUserDismissalPolicy.identity(CourierSignals.WOLT_PACKAGE, enriched),
            ),
        )
    }

    @Test
    fun differentWoltRouteIsNotSuppressed() {
        val dismissed = ParsedOffer(
            priceCents = 370,
            distanceMeters = 4_300,
            restaurant = "Holy Donut",
            pickupAddresses = listOf("Vokiečių g. 9, Vilnius"),
            dropoffAddresses = listOf("Manufaktūrų gatvė 29, Vilnius"),
            deliveryCount = 1,
        )
        val next = dismissed.copy(
            priceCents = 510,
            distanceMeters = 6_100,
            dropoffAddresses = listOf("Žirmūnų g. 55, Vilnius"),
        )

        assertFalse(
            LiveOfferUserDismissalPolicy.isSameOffer(
                LiveOfferUserDismissalPolicy.identity(CourierSignals.WOLT_PACKAGE, dismissed),
                LiveOfferUserDismissalPolicy.identity(CourierSignals.WOLT_PACKAGE, next),
            ),
        )
    }

    @Test
    fun oneSparseMatchingFieldIsNotEnoughToSuppressFutureOffer() {
        val dismissed = LiveOfferDismissalIdentity(
            packageName = CourierSignals.WOLT_PACKAGE,
            priceCents = 370,
            distanceMeters = 4_300,
            deliveryCount = 1,
            routeFingerprint = null,
        )
        val sparse = LiveOfferDismissalIdentity(
            packageName = CourierSignals.WOLT_PACKAGE,
            priceCents = 370,
            distanceMeters = null,
            deliveryCount = null,
            routeFingerprint = null,
        )

        assertFalse(LiveOfferUserDismissalPolicy.isSameOffer(dismissed, sparse))
    }
}
