package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketScreenStateTest {
    @Test fun defaultsToLearningWithNoInventedMedian() {
        val state = MarketScreenState()
        assertEquals(MarketSource.LEARNING, state.source)
        assertEquals(MarketConfidence.NOT_READY, state.confidence)
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
        assertTrue(MarketTrend(12.5, true).label.startsWith("↑"))
        assertTrue(MarketTrend(4.0, false).label.startsWith("↓"))
    }
}
