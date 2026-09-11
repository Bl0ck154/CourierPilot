package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferDecisionV0153Test {

    private fun route(profile: RouteProfile, meters: Int) = RouteResult(
        provider = "valhalla",
        profile = profile,
        distanceMeters = meters,
        durationSeconds = 600,
        legShapes = emptyList(),
    )

    @Test
    fun ratingUsesAverageOfWalkingAndCyclingValhallaDistance() {
        val parsed = ParsedOffer(
            priceCents = 550,
            money = MoneyAmount(550, "EUR", 2),
            distanceMeters = 1_000,
            restaurant = "Test",
            estimatedMinutesMin = 10,
            estimatedMinutesMax = 12,
        )
        val decision = OfferDecisionEngine.evaluate(
            parsed,
            pedestrianRoute = route(RouteProfile.PEDESTRIAN_SHORTCUT, 5_000),
            cyclewayRoute = route(RouteProfile.CYCLEWAY_BIASED, 6_000),
        )
        assertEquals(5_500, decision.routeDistanceMeters)
        assertEquals(1.0, decision.moneyPerKilometer!!, 0.0001)
        assertEquals(OfferDecisionBand.UNKNOWN, decision.band)
        assertNull(decision.rating)
    }

    @Test
    fun moreThanOneMoneyUnitPerKmIsGood() {
        val parsed = ParsedOffer(priceCents = 600, money = MoneyAmount(600, "EUR", 2), distanceMeters = 500, restaurant = null)
        val decision = OfferDecisionEngine.evaluate(
            parsed,
            pedestrianRoute = route(RouteProfile.PEDESTRIAN_SHORTCUT, 5_000),
            cyclewayRoute = route(RouteProfile.CYCLEWAY_BIASED, 6_000),
        )
        assertTrue(decision.moneyPerKilometer!! > 1.0)
        assertEquals(OfferDecisionBand.UNKNOWN, decision.band)
        assertNull(decision.rating)
    }

    @Test
    fun highMoneyPerKmGetsFire() {
        val parsed = ParsedOffer(priceCents = 700, money = MoneyAmount(700, "EUR", 2), distanceMeters = 100, restaurant = null)
        val decision = OfferDecisionEngine.evaluate(
            parsed,
            pedestrianRoute = route(RouteProfile.PEDESTRIAN_SHORTCUT, 5_000),
            cyclewayRoute = route(RouteProfile.CYCLEWAY_BIASED, 6_000),
        )
        assertTrue(decision.moneyPerKilometer!! >= 1.25)
        assertEquals(OfferDecisionBand.UNKNOWN, decision.band)
        assertNull(decision.rating)
    }

    @Test
    fun platformDistanceNeverCreatesRatingBeforeValhalla() {
        val parsed = ParsedOffer(
            priceCents = 900,
            money = MoneyAmount(900, "EUR", 2),
            distanceMeters = 500,
            restaurant = null,
            estimatedMinutesMin = 5,
            estimatedMinutesMax = 5,
        )
        val decision = OfferDecisionEngine.evaluate(parsed)
        assertEquals(OfferDecisionBand.UNKNOWN, decision.band)
        assertNull(decision.rating)
        assertNull(decision.moneyPerKilometer)
        assertNull(decision.routeDistanceMeters)
    }

    @Test
    fun cityMarketThresholdsCanRaiseTheMeaningOfAGoodOffer() {
        val parsed = ParsedOffer(priceCents = 600, money = MoneyAmount(600, "EUR", 2), distanceMeters = 100, restaurant = null)
        val decision = OfferDecisionEngine.evaluate(
            parsed,
            pedestrianRoute = route(RouteProfile.PEDESTRIAN_SHORTCUT, 5_000),
            cyclewayRoute = null,
            thresholds = OfferDecisionThresholds(0.90, 1.05, 1.20, 1.45),
        )
        assertEquals(1.2, decision.moneyPerKilometer!!, 0.0001)
        assertEquals(OfferDecisionBand.OK, decision.band)
        assertEquals(3, decision.rating)
    }

    @Test
    fun cityMarketThresholdsCanLowerTheMeaningOfAFireOffer() {
        val parsed = ParsedOffer(priceCents = 500, money = MoneyAmount(500, "EUR", 2), distanceMeters = 100, restaurant = null)
        val decision = OfferDecisionEngine.evaluate(
            parsed,
            pedestrianRoute = route(RouteProfile.PEDESTRIAN_SHORTCUT, 5_000),
            cyclewayRoute = null,
            thresholds = OfferDecisionThresholds(0.55, 0.70, 0.82, 0.95),
        )
        assertEquals(1.0, decision.moneyPerKilometer!!, 0.0001)
        assertEquals(OfferDecisionBand.FIRE, decision.band)
        assertEquals(5, decision.rating)
    }

    @Test
    fun legacyPriceCentsWithoutExplicitMoneyDoesNotAssumeEuro() {
        val parsed = ParsedOffer(priceCents = 600, distanceMeters = 100, restaurant = null)
        val decision = OfferDecisionEngine.evaluate(
            parsed,
            pedestrianRoute = route(RouteProfile.PEDESTRIAN_SHORTCUT, 5_000),
            thresholds = OfferDecisionThresholds(0.8, 0.9, 1.0, 1.1),
        )
        assertNull(decision.moneyPerKilometer)
        assertNull(decision.currencyCode)
        assertEquals(OfferDecisionBand.UNKNOWN, decision.band)
    }

    @Test
    fun oneSuccessfulValhallaProfileIsUsedAsFallback() {
        val parsed = ParsedOffer(priceCents = 500, money = MoneyAmount(500, "EUR", 2), distanceMeters = 100, restaurant = null)
        val decision = OfferDecisionEngine.evaluate(
            parsed,
            pedestrianRoute = route(RouteProfile.PEDESTRIAN_SHORTCUT, 5_000),
            cyclewayRoute = null,
        )
        assertEquals(5_000, decision.routeDistanceMeters)
        assertEquals(1.0, decision.moneyPerKilometer!!, 0.0001)
    }

    @Test
    fun eurColdStartUsesTheEstablishedCourierScale() {
        val thresholds = LiveOfferColdStartThresholds.forCurrency("EUR")!!
        assertEquals(OfferDecisionBand.GOOD, OfferDecisionEngine.bandFor(1.10, thresholds))
        assertEquals(OfferDecisionBand.FIRE, OfferDecisionEngine.bandFor(2.97, thresholds))
    }

    @Test
    fun nonEuroColdStartNeverGuessesFxEquivalentThresholds() {
        assertNull(LiveOfferColdStartThresholds.forCurrency("PLN"))
        assertNull(LiveOfferColdStartThresholds.forCurrency(null))
    }
}
