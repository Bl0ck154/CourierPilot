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
        assertEquals("€2.97/km  🔥", line)
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
        assertEquals("PLN 5.25/km  👍", line)
        assertFalse(line.contains("€"))
        assertFalse(line.contains("/h"))
    }

    @Test
    fun provisionalRateAppearsBeforeRealRouteAndHasNoVerdictEmoji() {
        val line = LiveAdvisorPresentation.provisionalRateLine(
            money = MoneyAmount(533, "EUR", 2),
            estimatedRouteMeters = 7500,
        )
        assertEquals("≈€0.71/km  ⏳", line)
        assertFalse(line!!.contains("💩"))
        assertFalse(line.contains("🔥"))
    }

    @Test
    fun addonProvisionalRateDoesNotExposePlatformSourceInUi() {
        val line = LiveAdvisorPresentation.provisionalRateLine(
            money = MoneyAmount(278, "EUR", 2),
            estimatedRouteMeters = 2300,
        )
        assertEquals("≈€1.21/km  ⏳", line)
        assertFalse(line!!.contains("Wolt", ignoreCase = true))
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
        assertEquals("🚶 4.10 km\n🚲 4.50 km", line)
    }

    @Test
    fun rateLineSplitsIntoNumberUnitAndEmoji() {
        assertEquals(LiveAdvisorRateParts("€1.43", "/km", "👍"), LiveAdvisorRateStylePolicy.split("€1.43/km  👍"))
        assertEquals(LiveAdvisorRateParts("≈€1.20", "/km", "⏳"), LiveAdvisorRateStylePolicy.split("≈€1.20/km  ⏳"))
        assertEquals(LiveAdvisorRateParts("PLN 5.25", "/km", "🔥"), LiveAdvisorRateStylePolicy.split("PLN 5.25/km  🔥"))
        assertEquals(LiveAdvisorRateParts("?", "/km", ""), LiveAdvisorRateStylePolicy.split("?/km"))
        assertEquals(LiveAdvisorRateParts("—", "/km", ""), LiveAdvisorRateStylePolicy.split("—/km"))
    }

    @Test
    fun worseBandsAreNeverMoreVividThanBetterOnes() {
        val ordered = listOf(
            OfferDecisionBand.FIRE,
            OfferDecisionBand.GOOD,
            OfferDecisionBand.OK,
            OfferDecisionBand.BAD,
            OfferDecisionBand.TERRIBLE,
        ).map { LiveAdvisorRateStylePolicy.style(it, estimate = false) }
        ordered.zipWithNext().forEach { (better, worse) ->
            assertTrue(better.emojiSaturation >= worse.emojiSaturation)
            assertTrue(better.emojiAlpha >= worse.emojiAlpha)
            assertTrue(luminance(better.color) >= luminance(worse.color))
        }
        assertTrue(ordered.first().glowRadiusDp > 0f)
        assertEquals(0f, ordered.last().glowRadiusDp)
    }

    @Test
    fun estimatesStayNeutralWhateverTheBand() {
        val estimate = LiveAdvisorRateStylePolicy.style(OfferDecisionBand.FIRE, estimate = true)
        assertEquals(LiveAdvisorRateStylePolicy.style(OfferDecisionBand.UNKNOWN, estimate = false), estimate)
        assertEquals(0f, estimate.glowRadiusDp)
    }

    @Test
    fun longerValuesShrinkInsteadOfEllipsizing() {
        assertEquals(30f, LiveAdvisorRateStylePolicy.valueTextSp("€1.43"))
        assertEquals(26f, LiveAdvisorRateStylePolicy.valueTextSp("≈€12.40"))
        assertEquals(22f, LiveAdvisorRateStylePolicy.valueTextSp("PLN 12.40"))
    }

    private fun luminance(color: Int): Int =
        ((color shr 16) and 0xff) * 3 + ((color shr 8) and 0xff) * 6 + (color and 0xff)
}
