package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformDistanceTrendsTest {
    private val now = 1_798_012_800_000L // fixed 2026-12-25, no time-sensitive tests

    private fun offer(
        price: Int = 600, distance: Int? = 3_000, platform: String = "Wolt",
        currency: String = "EUR", raw: String = "", at: Long = now - 1_000L,
        fractions: Int = 2,
    ) = OfferRecord(
        capturedAt = at, platform = platform,
        packageName = if (platform == "Bolt") CourierSignals.BOLT_PACKAGE else CourierSignals.WOLT_PACKAGE,
        priceCents = price, currencyCode = currency,
        currencyFractionDigits = fractions, distanceMeters = distance,
        restaurant = "Example", screenshotUri = "", screenshotFilename = "",
        rawText = raw,
    )

    @Test fun localPlatformMediansNeverIncludeWrongCurrencyOrOtherPlatform() {
        val records = listOf(
            offer(price = 600, distance = 3_000),
            offer(price = 800, distance = 2_000),
            offer(price = 300, distance = 1_000, currency = "PLN"),
            offer(price = 300, distance = 1_000, platform = "Bolt"),
        )
        val result = PlatformDistanceTrends.buckets(
            records, "Wolt", "EUR", MarketHistoryPeriod.MONTH, now,
        )
        assertEquals(1, result.size)
        assertEquals(2, result.single().sampleCount)
        assertEquals("3.00", result.single().median)
        assertEquals("2.50", result.single().p25)
        assertEquals("3.50", result.single().p75)
    }

    @Test fun missingDistanceAndPayoutAreExcludedEvenForBolt() {
        val records = listOf(
            offer(platform = "Bolt", distance = null),
            offer(platform = "Bolt", distance = 0),
            offer(platform = "Bolt", distance = 2000, price = 0),
        )
        assertTrue(PlatformDistanceTrends.buckets(
            records, "Bolt", "EUR", MarketHistoryPeriod.WEEK, now,
        ).isEmpty())
    }

    @Test fun incrementalWoltOfferIsNeverComparedWithFullOffers() {
        val incremental = """
            +€2.78
            +2 stops (2.3 km) • 5–12 min extra
            Store (Vilnius)
            Estimated earnings for the full delivery
            Accept
            Decline
        """.trimIndent()
        assertTrue(OfferParser.parse(incremental).isIncrementalOffer)
        val result = PlatformDistanceTrends.buckets(
            listOf(offer(raw = incremental, price = 278, distance = 2_300)),
            "Wolt", "EUR", MarketHistoryPeriod.DAY, now,
        )
        assertTrue(result.isEmpty())
    }

    @Test fun oldObservationsStayOutOfNinetyDayWindow() {
        val old = offer(at = now - 91L * 86_400_000L)
        assertTrue(PlatformDistanceTrends.buckets(
            listOf(old), "Wolt", "EUR", MarketHistoryPeriod.MONTH, now,
        ).isEmpty())
    }

    @Test fun nativeCurrencyFractionDigitsAreHonoured() {
        val result = PlatformDistanceTrends.buckets(
            listOf(offer(price = 600, distance = 2_000, currency = "HUF", fractions = 0)),
            "Wolt", "HUF", MarketHistoryPeriod.MONTH, now,
        )
        assertEquals("300.00", result.single().median)
    }

    @Test fun newestCapturedOfferSetsPlatformOnlyCurrency() {
        assertEquals("PLN", PlatformDistanceTrends.recentCurrency(
            listOf(offer(currency = "PLN", price = 100), offer(currency = "EUR")), "Wolt",
        ))
    }
}
