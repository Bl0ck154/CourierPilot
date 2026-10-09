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

    @Test
    fun boltSamePriceAndPickupIsSuppressedWithoutDistanceOrCount() {
        val base = ParsedOffer(
            priceCents = 241,
            distanceMeters = null,
            restaurant = "Casa Della Pasta",
            pickupAddresses = listOf("Vokiečių gatvė 13, Vilnius"),
        )
        val sparse = base.copy(
            pickupAddresses = listOf("Vokiečių g. 13, Vilnius"),
            restaurant = null,
            merchantNames = emptyList(),
        )
        val initial = LiveOfferUserDismissalPolicy.identity(CourierSignals.BOLT_PACKAGE, base)
        val updated = LiveOfferUserDismissalPolicy.identity(CourierSignals.BOLT_PACKAGE, sparse)
        assertTrue(initial.pickupKey != null)
        assertTrue(LiveOfferUserDismissalPolicy.isSameOffer(initial, updated))
    }

    @Test
    fun boltDifferentPickupAndMerchantIsDifferentOfferEvenAtSamePrice() {
        val first = ParsedOffer(
            priceCents = 241,
            distanceMeters = null,
            restaurant = "Casa Della Pasta",
            pickupAddresses = listOf("Vokiečių gatvė 13, Vilnius"),
        )
        val second = first.copy(
            restaurant = "Sushi Square",
            pickupAddresses = listOf("Vilniaus g. 47, Vilnius"),
        )
        assertFalse(
            LiveOfferUserDismissalPolicy.isSameOffer(
                LiveOfferUserDismissalPolicy.identity(CourierSignals.BOLT_PACKAGE, first),
                LiveOfferUserDismissalPolicy.identity(CourierSignals.BOLT_PACKAGE, second),
            ),
        )
    }

    @Test
    fun boltDurationAndDeliveryCountContradictionsAreNotSuppressed() {
        val base = ParsedOffer(
            priceCents = 241,
            distanceMeters = null,
            restaurant = "Casa Della Pasta",
            deliveryCount = 1,
            estimatedMinutesMin = 15,
        )
        val dismissed = LiveOfferUserDismissalPolicy.identity(CourierSignals.BOLT_PACKAGE, base)
        assertFalse(LiveOfferUserDismissalPolicy.isSameOffer(
            dismissed, LiveOfferUserDismissalPolicy.identity(CourierSignals.BOLT_PACKAGE, base.copy(estimatedMinutesMin = 22)),
        ))
        assertFalse(LiveOfferUserDismissalPolicy.isSameOffer(
            dismissed, LiveOfferUserDismissalPolicy.identity(CourierSignals.BOLT_PACKAGE, base.copy(deliveryCount = 2)),
        ))
    }
}
