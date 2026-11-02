package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketScreenStateTest {
    @Test fun defaultsToLearningWithNoInventedMedian() {
        val state = MarketScreenState()
        assertEquals(MarketSource.LEARNING, state.source)
        assertEquals(MarketUiConfidence.NOT_READY, state.confidence)
        assertEquals(null, state.personalMedian)
        assertEquals(null, state.cityMedian)
    }

    @Test fun learningProgressIsBoundedByFive() {
        val state = MarketScreenState(sampleCount = 8, learningTarget = 5)
        assertEquals(5, state.sampleCount.coerceAtMost(state.learningTarget))
    }

    @Test fun historyCarriesNativeCurrencyRangeAndCount() {
        val bucket = MarketHistoryBucket("2026-09-01", "620", "500", "800", 12)
        assertEquals(12, bucket.sampleCount)
        assertEquals("620", bucket.median)
    }

    @Test fun trendUsesDirectionAndPercent() {
        assertTrue(MarketUiTrend(12.5, true).label.startsWith("↑"))
        assertTrue(MarketUiTrend(4.0, false).label.startsWith("↓"))
    }
    @Test fun payUnitRespectsCurrentCurrency() {
        assertEquals("€/km", MarketScreenState(currencyCode = "EUR").payRateUnit)
        assertEquals("PLN/km", MarketScreenState(currencyCode = "PLN").payRateUnit)
        assertEquals("CZK/km", MarketScreenState(currencyCode = "czk").payRateUnit)
    }

    @Test fun marketSampleCountIdentifiesItsSource() {
        assertEquals("eligible personal offers", MarketScreenState(source = MarketSource.PERSONAL_AND_CITY).offerCountDescription)
        assertEquals("eligible city offers", MarketScreenState(source = MarketSource.CITY).offerCountDescription)
        assertEquals("eligible offers", MarketScreenState(source = MarketSource.LEARNING).offerCountDescription)
    }

}
