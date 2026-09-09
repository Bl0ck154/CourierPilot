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

    @Test
    fun partialWoltComparisonIsNotPersistedAsTrustedEconomics() {
        val ordinary = ParsedOffer(
            priceCents = 886,
            money = MoneyAmount(886, "EUR", 2),
            distanceMeters = 11_400,
            restaurant = "GOGI GUY",
        )
        val comparison = RouteComparison(
            pedestrian = Result.failure(IllegalStateException("temporary walking failure")),
            cycleway = Result.success(
                RouteResult(
                    provider = "test",
                    profile = RouteProfile.CYCLEWAY_BIASED,
                    distanceMeters = 10_226,
                    durationSeconds = 1_000,
                    legShapes = emptyList(),
                )
            ),
        )

        assertFalse(MarketRoutePersistencePolicy.shouldPersistFullRoute("Wolt", ordinary, comparison))
        assertTrue(MarketRoutePersistencePolicy.shouldPersistFullRoute("Bolt", ordinary, comparison))
    }
}
