package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAdvisorPresentationTest {
    @Test
    fun rateLineShowsOnlyPrimaryEuroPerKilometerAndEmoji() {
        val decision = OfferDecision(
            rating = 5,
            band = OfferDecisionBand.FIRE,
            euroPerKilometer = 2.97,
            routeDistanceMeters = 1744,
            routeVerifiedKilometerRate = true,
            currencyCode = "EUR",
        )

        val line = LiveAdvisorPresentation.rateLine(decision)

        assertEquals("≈ €2.97/km  🔥", line)
        assertEquals(1, Regex("/km").findAll(line).count())
        assertFalse(line.contains("/h"))
    }

    @Test
    fun rateLineUsesExplicitNonEuroCurrencyWithoutInventingEuro() {
        val decision = OfferDecision(
            rating = 4,
            band = OfferDecisionBand.GOOD,
            euroPerKilometer = 5.25,
            routeDistanceMeters = 4000,
            routeVerifiedKilometerRate = true,
            currencyCode = "PLN",
        )

        val line = LiveAdvisorPresentation.rateLine(decision)

        assertEquals("≈ PLN 5.25/km  👍", line)
        assertFalse(line.contains("€"))
        assertFalse(line.contains("/h"))
    }

    @Test
    fun routeLineShowsOnlyWalkingAndCyclingProfiles() {
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
        assertFalse(line.contains("≈"))
        assertFalse(line.contains("4.30 km"))
        assertFalse(line.contains("avg", ignoreCase = true))
    }
}
