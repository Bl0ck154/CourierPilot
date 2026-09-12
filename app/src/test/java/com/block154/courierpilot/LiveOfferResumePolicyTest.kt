package com.block154.courierpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOfferResumePolicyTest {
    private val expected = ParsedOffer(
        priceCents = 450,
        distanceMeters = null,
        restaurant = "Hesburger",
        merchantNames = listOf("Hesburger"),
        pickupAddresses = listOf("Vokiečių g. 12"),
    )

    @Test
    fun sameOfferCanResumeFromSparseVisibleData() {
        val visible = ParsedOffer(
            priceCents = 450,
            distanceMeters = null,
            restaurant = "Hesburger",
        )

        assertFalse(LiveOfferResumePolicy.definitelyDifferent(expected, visible))
    }

    @Test
    fun differentPricePreventsOldCardRestore() {
        val visible = expected.copy(priceCents = 620)
        assertTrue(LiveOfferResumePolicy.definitelyDifferent(expected, visible))
    }

    @Test
    fun differentMerchantPreventsOldCardRestore() {
        val visible = expected.copy(
            restaurant = "McDonald's",
            merchantNames = listOf("McDonald's"),
            pickupAddresses = emptyList(),
        )
        assertTrue(LiveOfferResumePolicy.definitelyDifferent(expected, visible))
    }

    @Test
    fun differentPickupPreventsOldCardRestoreWhenBothSidesExposeIt() {
        val visible = expected.copy(pickupAddresses = listOf("Gedimino pr. 9"))
        assertTrue(LiveOfferResumePolicy.definitelyDifferent(expected, visible))
    }

    @Test
    fun matchingPriceKeepsWoltOfferAliveWhenControlsTemporarilyDisappear() {
        val visible = ParsedOffer(
            priceCents = 450,
            distanceMeters = null,
            restaurant = null,
        )
        assertTrue(LiveOfferResumePolicy.hasMatchingIdentity(expected, visible))
    }

    @Test
    fun emptySparseFrameDoesNotPretendToMatchIdentity() {
        val visible = ParsedOffer(
            priceCents = null,
            distanceMeters = null,
            restaurant = null,
        )
        assertFalse(LiveOfferResumePolicy.hasMatchingIdentity(expected, visible))
    }

    @Test
    fun matchingPriceDoesNotOverrideConflictingMerchantIdentity() {
        val visible = ParsedOffer(
            priceCents = 450,
            distanceMeters = null,
            restaurant = "McDonald's",
            merchantNames = listOf("McDonald's"),
        )
        assertFalse(LiveOfferResumePolicy.hasMatchingIdentity(expected, visible))
    }

    @Test
    fun addOnOfferWithOldDropoffPlusNewStopsIsAReplacementNotSameScreen() {
        val single = expected.copy(
            dropoffAddresses = listOf("Arklių gatvė 36, Vilnius"),
            deliveryCount = 1,
        )
        val expanded = single.copy(
            dropoffAddresses = listOf(
                "Arklių gatvė 36, Vilnius",
                "Žirmūnų g. 54, Vilnius",
                "Ozo g. 18, Vilnius",
            ),
            deliveryCount = 3,
        )

        assertTrue(LiveOfferResumePolicy.isStrictRouteExtension(single, expanded))
        assertTrue(LiveOfferResumePolicy.definitelyDifferent(single, expanded))
        assertFalse(LiveOfferResumePolicy.hasMatchingIdentity(single, expanded))
    }

    @Test
    fun stablePickupPreventsNoisyMerchantOcrFromRearmingSameWoltOffer() {
        val visible = expected.copy(
            restaurant = "OCR garbage",
            merchantNames = listOf("OCR garbage"),
            pickupAddresses = listOf("Vokiečių g. 12"),
        )
        assertFalse(LiveOfferResumePolicy.definitelyDifferent(expected, visible))
        assertTrue(LiveOfferResumePolicy.hasMatchingIdentity(expected, visible))
    }

    @Test
    fun collapsedBatchCardKeepsSameCoreIdentityAfterHiddenDropoffsWereRecovered() {
        val enriched = ParsedOffer(
            priceCents = 886,
            distanceMeters = 11_400,
            restaurant = "GOGI GUY",
            merchantNames = listOf("GOGI GUY"),
            pickupAddresses = listOf("Rūdninkų g. 15, Vilnius, LT01308"),
            dropoffAddresses = listOf("Customer A", "Customer B"),
            deliveryCount = 2,
        )
        val collapsed = ParsedOffer(
            priceCents = 886,
            distanceMeters = 11_400,
            restaurant = "Ready in 7 min",
            merchantNames = listOf("Ready in 7 min"),
            pickupAddresses = emptyList(),
            dropoffAddresses = emptyList(),
            deliveryCount = 2,
        )

        assertTrue(LiveOfferResumePolicy.hasCompatibleCoreIdentity(enriched, collapsed))
    }

    @Test
    fun identicalNumericFingerprintIgnoresComposeTextLossFromReal01582Trace() {
        val captured = ParsedOffer(
            priceCents = 626,
            distanceMeters = 5_300,
            restaurant = "Holy Donut",
            merchantNames = listOf("Holy Donut", "Holy Donut (branch)"),
            pickupAddresses = listOf("Vilniaus g. 18"),
            dropoffAddresses = listOf("Customer drop-off"),
            deliveryCount = 1,
        )
        val recomposed = ParsedOffer(
            priceCents = 626,
            distanceMeters = 5_300,
            restaurant = "Ready for pickup",
            merchantNames = emptyList(),
            pickupAddresses = listOf("Temporary OCR pickup text"),
            dropoffAddresses = listOf("Temporary OCR dropoff text"),
            deliveryCount = 1,
        )

        assertTrue(LiveOfferResumePolicy.hasStableNumericFingerprint(captured, recomposed))
        assertFalse(LiveOfferResumePolicy.definitelyDifferent(captured, recomposed))
        assertTrue(LiveOfferResumePolicy.hasMatchingIdentity(captured, recomposed))
    }

    @Test
    fun changedCoreNumberStillReplacesEvenWhenTextLooksSimilar() {
        val captured = ParsedOffer(
            priceCents = 626,
            distanceMeters = 5_300,
            restaurant = "Holy Donut",
            deliveryCount = 1,
        )
        val changedPrice = captured.copy(priceCents = 783)
        val changedDistance = captured.copy(distanceMeters = 6_800)
        val changedDeliveries = captured.copy(deliveryCount = 2)

        assertFalse(LiveOfferResumePolicy.hasStableNumericFingerprint(captured, changedPrice))
        assertTrue(LiveOfferResumePolicy.definitelyDifferent(captured, changedPrice))
        assertFalse(LiveOfferResumePolicy.hasStableNumericFingerprint(captured, changedDistance))
        assertTrue(LiveOfferResumePolicy.definitelyDifferent(captured, changedDistance))
        assertFalse(LiveOfferResumePolicy.hasStableNumericFingerprint(captured, changedDeliveries))
        assertTrue(LiveOfferResumePolicy.definitelyDifferent(captured, changedDeliveries))
    }

    @Test
    fun routeExtensionDoesNotUseCollapsedCoreIdentityShortcut() {
        val existing = ParsedOffer(
            priceCents = 886,
            distanceMeters = 11_400,
            restaurant = "GOGI GUY",
            pickupAddresses = listOf("Rūdninkų g. 15"),
            dropoffAddresses = listOf("A", "B"),
            deliveryCount = 2,
        )
        val extension = existing.copy(
            dropoffAddresses = listOf("A", "B", "C"),
            deliveryCount = 3,
        )

        assertFalse(LiveOfferResumePolicy.hasCompatibleCoreIdentity(existing, extension))
    }
}
