package com.block154.courierpilot

import org.junit.Assert.*
import org.junit.Test

class MarketV2SyncTest {
    private val sample = MarketV2Upload(installId="anon", offerId="o1", capturedAt=1,
        cityKey="lt-vilnius", cityName="Vilnius", countryCode="LT", platform="Wolt",
        currencyCode="EUR", priceMinor=438, currencyFractionDigits=2, fullRouteDistanceM=5000,
        routeSource="FULL", deliveryCount=1, localHour=12, localWeekday=2, appVersion="1", versionCode=1)

    @Test fun payloadAllowListHasNoPrivateFields() {
        val fields = sample.toFields().keys
        assertFalse(fields.any { it.contains("name", true) && it != "city_name" })
        assertFalse(fields.any { it.contains("address", true) || it.contains("ocr", true) || it.contains("screenshot", true) || it.contains("gps", true) })
    }

    @Test fun queueDeduplicatesAndRetries() {
        var attempts = 0
        val service = object : MarketV2Service {
            override fun upload(sample: MarketV2Upload): Boolean = ++attempts > 1
            override fun profile(c: MarketV2Cohort, hour: Int?, weekday: Int?) = null
            override fun history(c: MarketV2Cohort, period: String) = emptyList<MarketV2HistoryBucket>()
        }
        val repo = MarketV2Repository(service); repo.enqueue(sample); repo.enqueue(sample)
        assertEquals(1, repo.pendingCount()); assertEquals(0, repo.flush()); assertEquals(1, repo.pendingCount())
        assertEquals(1, repo.flush()); assertEquals(0, repo.pendingCount())
    }

    @Test fun historyPeriodsAreExplicitAndProfileIsExactCohort() {
        val cohort = MarketV2Cohort("lt-vilnius", "EUR", "Wolt")
        val service = object : MarketV2Service {
            override fun upload(sample: MarketV2Upload) = true
            override fun profile(c: MarketV2Cohort, hour: Int?, weekday: Int?) = MarketV2Profile(false, 0, 0.0, 0, null, null, null, null, null)
            override fun history(c: MarketV2Cohort, period: String) = emptyList<MarketV2HistoryBucket>()
        }
        val repo = MarketV2Repository(service)
        assertNotNull(repo.getProfile(cohort)); assertTrue(repo.getHistory(cohort, "month").isEmpty())
    }
}
