package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMarketScoringTest {
    @Test
    fun singleThreeEuroPerKmOfferDoesNotRedefinePersonalMarket() {
        val normal = listOf(0.88, 0.92, 0.95, 0.98, 1.00, 1.02, 1.05, 1.08, 1.10, 1.12, 1.15)
        val profile = LocalMarketScoring.profile(normal + 3.00)!!

        assertEquals(12, profile.sampleCount)
        assertTrue(profile.medianNativeMoneyPerKm < 1.10)
        assertTrue(profile.thresholds.goodBelow < 1.20)
    }

    @Test
    fun repeatedHigherOffersMovePersonalMarketUp() {
        val oldMarket = listOf(0.85, 0.90, 0.95, 0.98, 1.00, 1.02, 1.04, 1.06, 1.08, 1.10)
        val strongerMarket = oldMarket + listOf(1.35, 1.40, 1.45, 1.50, 1.55, 1.60, 1.65, 1.70)

        val old = LocalMarketScoring.profile(oldMarket)!!
        val stronger = LocalMarketScoring.profile(strongerMarket)!!

        assertTrue(stronger.medianNativeMoneyPerKm > old.medianNativeMoneyPerKm)
        assertTrue(stronger.thresholds.goodBelow > old.thresholds.goodBelow)
    }

    @Test
    fun localHistoryDominatesCityProfileAsSampleCountGrows() {
        val local = LocalMarketProfile(
            sampleCount = 45,
            medianNativeMoneyPerKm = 1.35,
            thresholds = OfferDecisionThresholds(0.95, 1.10, 1.30, 1.55),
            source = "local_platform",
        )
        val city = OfferDecisionThresholds(0.65, 0.80, 0.95, 1.15)
        val combined = requireNotNull(LocalMarketScoring.combine(local, city))

        assertEquals(45.0 / 53.0, LocalMarketScoring.localWeight(45), 0.0001)
        assertTrue(combined.okAtMost > 1.20)
        assertTrue(combined.okAtMost < local.thresholds.okAtMost)
    }
}
