package com.block154.courierpilot

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class OfferEnrichmentCacheTest {
    private val raw = """
        €12.00
        4 stops (8.3 km) • 26–39 min
        Ponas Mėsainis (Kauno g.)
        Kauno g. 13, Vilnius, LT03128
        The Urban Garden
        J.Basanaviciaus g. 3, Vilnius, LT 01118
        Multiple drop-offs (2 stops)
        Accept
    """.trimIndent()

    private fun record(id: Long, rawText: String = raw) = OfferRecord(
        id = id,
        capturedAt = 1L,
        platform = "Wolt",
        packageName = CourierSignals.WOLT_PACKAGE,
        priceCents = 1200,
        distanceMeters = null,
        restaurant = null,
        screenshotUri = "",
        screenshotFilename = "",
        rawText = rawText,
    )

    @Test
    fun unchangedRowIsParsedOnce() {
        OfferEnrichmentCache.clear()
        val first = OfferEnrichmentCache.enriched(record(7))
        assertSame(first, OfferEnrichmentCache.cached(record(7)))
        assertEquals(listOf("Ponas Mėsainis (Kauno g.)", "The Urban Garden"), first.merchantNames)
    }

    @Test
    fun changedRowIsNotServedFromCache() {
        OfferEnrichmentCache.clear()
        OfferEnrichmentCache.enriched(record(8))
        assertNull(OfferEnrichmentCache.cached(record(8, raw + "\nDecline")))
    }

    @Test
    fun enrichAllKeepsOrder() = runBlocking {
        OfferEnrichmentCache.clear()
        val ids = (1L..12L).toList()
        assertEquals(ids, OfferEnrichmentCache.enrichAll(ids.map { record(it) }).map { it.id })
    }
}
