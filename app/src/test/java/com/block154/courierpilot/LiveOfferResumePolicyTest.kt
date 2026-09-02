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
}
