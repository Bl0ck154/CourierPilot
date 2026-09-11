package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAdvisorPresentationTest {
    @Test
    fun rateLineShowsOnlyPrimaryMoneyPerKilometerAndEmoji() {
        val decision = OfferDecision(
            rating = 5,
            band = OfferDecisionBand.FIRE,
            moneyPerKilometer = 2.97,
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
            moneyPerKilometer = 5.25,
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
    fun provisionalRateAppearsBeforeRealRouteAndHasNoVerdictEmoji() {
        val line = LiveAdvisorPresentation.provisionalRateLine(
            money = MoneyAmount(533, "EUR", 2),
            estimatedRouteMeters = 7500,
        )
        assertEquals("≈ €0.71/km  ⏳", line)
        assertFalse(line!!.contains("💩"))
        assertFalse(line.contains("🔥"))
    }

    @Test
    fun addonProvisionalRateCanBeLabelledAsWoltIncrementalDistance() {
        val line = LiveAdvisorPresentation.provisionalRateLine(
            money = MoneyAmount(278, "EUR", 2),
            estimatedRouteMeters = 2300,
            marker = "Wolt",
        )
        assertEquals("≈ €1.21/km  Wolt", line)
    }

    @Test
    fun platformDistanceFallbackShowsDistanceWithoutInventingProfitability() {
        assertEquals("📍 9.10 km", LiveAdvisorPresentation.platformDistanceLine(9100))
        assertEquals("📍 3.90 km", LiveAdvisorPresentation.platformDistanceLine(3900))
    }

    @Test
    fun routeLineShowsOnlyWalkingAndCyclingProfiles() {
        val walking = RouteResult("test", RouteProfile.PEDESTRIAN_SHORTCUT, 4100, 900, emptyList())
        val cycling = RouteResult("test", RouteProfile.CYCLEWAY_BIASED, 4500, 700, emptyList())
        val line = LiveAdvisorPresentation.routeLine(walking, cycling)
        assertTrue(line.contains("🚶 4.10 km"))
        assertTrue(line.contains("🚲 4.50 km"))
        assertFalse(line.contains("≈"))
        assertFalse(line.contains("4.30 km"))
        assertFalse(line.contains("avg", ignoreCase = true))
    }
}
