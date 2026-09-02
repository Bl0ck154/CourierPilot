package com.block154.courierpilot

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MarketObservationDatabaseTest {
    @Test
    fun currencyPlatformAndHistoryWindowsStayIsolated() {
        val context: Context = RuntimeEnvironment.getApplication()
        val db = OfferDatabase.get(context)
        val now = System.currentTimeMillis()
        val seed = (System.nanoTime() and Long.MAX_VALUE).coerceAtLeast(10_000L)
        val city = "test-market-${seed.toString().takeLast(8)}"

        fun sample(
            id: Long,
            ageDays: Long,
            platform: String,
            currency: String,
            amountMinor: Long,
            digits: Int,
        ) = MarketObservation(
            offerId = id,
            capturedAt = now - ageDays * 86_400_000L,
            cityKey = city,
            cityName = "Test City",
            countryCode = "LT",
            platform = platform,
            money = MoneyAmount(amountMinor, currency, digits),
            fullRouteDistanceMeters = 5_000,
            routeSource = "FULL_valhalla_mean",
            deliveryCount = 1,
            localHour = 17,
            localWeekday = 3,
        )

        assertTrue(db.saveMarketObservation(sample(seed, 2, "Wolt", "PLN", 1950, 2)))
        assertTrue(db.saveMarketObservation(sample(seed + 1, 45, "Wolt", "PLN", 1750, 2)))
        assertTrue(db.saveMarketObservation(sample(seed + 2, 1, "Bolt", "EUR", 500, 2)))

        val liveWoltPln = db.marketObservations(now - 30L * 86_400_000L, city, "PLN", "Wolt")
        assertEquals(1, liveWoltPln.size)
        assertEquals("PLN", liveWoltPln.single().money.currencyCode)
        assertEquals(1950L, liveWoltPln.single().money.amountMinor)

        val liveBoltEur = db.marketObservations(now - 30L * 86_400_000L, city, "EUR", "Bolt")
        assertEquals(1, liveBoltEur.size)
        assertEquals("EUR", db.latestMarketCurrency(city, "Bolt"))
        assertEquals("PLN", db.latestMarketCurrency(city, "Wolt"))

        val history = db.marketObservationBuckets(
            since = now - 730L * 86_400_000L,
            cityKey = city,
            currencyCode = "PLN",
            platform = "Wolt",
            bucket = MarketObservationBucket.MONTH,
        )
        assertEquals(2, history.values.sumOf { it.size })
    }
}
