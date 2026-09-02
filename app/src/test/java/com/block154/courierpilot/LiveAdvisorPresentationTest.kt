package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAdvisorPresentationTest {
    @Test
    fun profitabilityLineShowsKilometerRateOnlyOnce() {
        val decision = OfferDecision(
            rating = 4,
            band = OfferDecisionBand.GOOD,
            euroPerKilometer = 1.14,
            routeDistanceMeters = 4300,
            routeVerifiedKilometerRate = true,
            currencyCode = "EUR",
        )
        val economics = PlatformOfferEconomics(
            euroPerKilometer = 0.88,
            euroPerHourMin = 12.0,
            euroPerHourMax = 12.0,
            currencyCode = "EUR",
        )

        val line = LiveAdvisorPresentation.profitabilityLine(decision, economics)

        assertEquals(1, Regex("/km").findAll(line).count())
        assertTrue(line.contains("€1.14/km"))
        assertTrue(line.contains("€12.0/h"))
        assertFalse(line.contains("avg", ignoreCase = true))
    }

    @Test
    fun profitabilityLineUsesExplicitNonEuroCurrency() {
        val decision = OfferDecision(
            rating = 4,
            band = OfferDecisionBand.GOOD,
            euroPerKilometer = 5.25,
            routeDistanceMeters = 4000,
            routeVerifiedKilometerRate = true,
            currencyCode = "PLN",
        )
        val economics = PlatformOfferEconomics(
            euroPerKilometer = 5.25,
            euroPerHourMin = 42.0,
            euroPerHourMax = 42.0,
            currencyCode = "PLN",
        )

        val line = LiveAdvisorPresentation.profitabilityLine(decision, economics)

        assertTrue(line.contains("PLN 5.25/km"))
        assertTrue(line.contains("PLN 42.0/h"))
        assertFalse(line.contains("€"))
    }

    @Test
    fun routeLineShowsBothProfilesAndPlainAverageSymbol() {
        val walking = RouteResult(
            provider = "test",
            profile = RouteProfile.PEDESTRIAN_SHORTCUT,
            distanceMeters = 4100,
            durationSeconds = 900,
            legShapes = emptyList(),
        )
        val cycling = RouteResult(
            provider = "test",
            profile = RouteProfile.CYCLEWAY_BIASED,
            distanceMeters = 4500,
            durationSeconds = 700,
            legShapes = emptyList(),
        )

        val line = LiveAdvisorPresentation.routeLine(walking, cycling)

        assertTrue(line.contains("🚶 4.10 km"))
        assertTrue(line.contains("🚲 4.50 km"))
        assertTrue(line.contains("≈ 4.30 km"))
        assertFalse(line.contains("avg", ignoreCase = true))
    }
}
