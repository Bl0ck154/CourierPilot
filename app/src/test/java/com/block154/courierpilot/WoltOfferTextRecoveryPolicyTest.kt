package com.block154.courierpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WoltOfferTextRecoveryPolicyTest {
    @Test
    fun routeCompleteButMerchantMissingStillRequiresOcrBeforeHistoryPersist() {
        val parsed = ParsedOffer(
            priceCents = 504,
            merchantNames = emptyList(),
            pickupAddresses = listOf("Vokiečių g. 6, Vilnius, LT01130"),
            dropoffAddresses = listOf("Teatro gatvė 9-4, Vilnius, 03107"),
            deliveryCount = 1,
        )

        assertTrue(WoltOfferTextRecoveryPolicy.needsOcrBeforePersist(parsed, automaticRouting = true))
        assertTrue(WoltOfferTextRecoveryPolicy.needsOcrBeforePersist(parsed, automaticRouting = false))
    }

    @Test
    fun completeMerchantAndRouteCanPersistImmediately() {
        val parsed = ParsedOffer(
            priceCents = 504,
            merchantNames = listOf("Backstage cafe (Vokiečių g.)"),
            pickupAddresses = listOf("Vokiečių g. 6, Vilnius, LT01130"),
            dropoffAddresses = listOf("Teatro gatvė 9-4, Vilnius, 03107"),
            deliveryCount = 1,
        )

        assertFalse(WoltOfferTextRecoveryPolicy.needsOcrBeforePersist(parsed, automaticRouting = true))
    }
}
