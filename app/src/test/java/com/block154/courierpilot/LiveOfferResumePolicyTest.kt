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
