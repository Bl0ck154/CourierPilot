package com.block154.courierpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketRoutePersistencePolicyTest {
    @Test
    fun woltIncrementalOfferDoesNotPersistFullResolvedRouteAsEconomics() {
        val parsed = ParsedOffer(
            priceCents = 278,
            money = MoneyAmount(278, "EUR", 2),
            distanceMeters = 2_300,
            restaurant = "12 Restoranas (Mindaugo g.)",
            isIncrementalOffer = true,
        )

        assertFalse(MarketRoutePersistencePolicy.shouldPersistFullRoute("Wolt", parsed))
    }

    @Test
    fun ordinaryWoltAndBoltOffersStillPersistFullResolvedRoutes() {
        val ordinary = ParsedOffer(
            priceCents = 700,
            money = MoneyAmount(700, "EUR", 2),
            distanceMeters = 4_000,
            restaurant = "Venue",
        )

        assertTrue(MarketRoutePersistencePolicy.shouldPersistFullRoute("Wolt", ordinary))
        assertTrue(MarketRoutePersistencePolicy.shouldPersistFullRoute("Bolt", ordinary.copy(isIncrementalOffer = true)))
    }
}
