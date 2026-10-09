package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOfferVerdictCacheTest {
    private val bolt = ParsedOffer(
        priceCents = 241,
        distanceMeters = null,
        restaurant = "Casa Della Pasta",
        pickupAddresses = listOf("Vokiečių gatvė 13, Vilnius"),
    )

    @Test
    fun swipeNeverReattachesForPendingBaseOrHistoryOfSameSession() {
        // All three entry points must consult this same window-attachment policy.
        assertTrue(LiveOfferSessionVisibilityPolicy.shouldAttach(false, false))
        assertFalse(LiveOfferSessionVisibilityPolicy.shouldAttach(true, false)) // showPending
        assertFalse(LiveOfferSessionVisibilityPolicy.shouldAttach(true, false)) // showBase
        assertFalse(LiveOfferSessionVisibilityPolicy.shouldAttach(true, true)) // suspended history
        assertTrue(LiveOfferSessionVisibilityPolicy.shouldAttach(false, false)) // next offer
    }

    @Test
    fun sameHiddenSessionSurvivesPendingAndBaseButDifferentOfferIsNewSession() {
        val same = bolt.copy(restaurant = "Casa Della Pasta (Vokiečių str.)")
        val different = bolt.copy(
            priceCents = 398,
            pickupAddresses = listOf("Vilniaus g. 47, Vilnius"),
            restaurant = "Sushi Square",
        )
        assertTrue(LiveOfferSessionVisibilityPolicy.sameSession(true, bolt, same))
        assertTrue(LiveOfferSessionVisibilityPolicy.sameSession(true, bolt, same)) // base/persist
        assertFalse(LiveOfferSessionVisibilityPolicy.shouldAttach(true, false))
        assertFalse(LiveOfferSessionVisibilityPolicy.sameSession(true, bolt, different))
        assertFalse(LiveOfferSessionVisibilityPolicy.sameSession(false, bolt, same))
        assertTrue(LiveOfferSessionVisibilityPolicy.shouldAttach(false, false))
    }

    @Test
    fun sameOfferAndHistoryIdRestoreIdenticalLineBandAndThresholdSource() {
        var time = 100L
        val cache = LiveOfferVerdictCache(nowMs = { time })
        val first = verdict(time)
        assertEquals(first, cache.remember("Bolt", bolt, 42L, first))
        // A new card generation, with a different live GPS fix, cannot replace the first verdict.
        time += 1_000L
        val other = verdict(time).copy(rateLine = "€0.34/km 👎", band = OfferDecisionBand.BAD, thresholdSource = "adaptive")
        assertEquals(first, cache.remember("Bolt", bolt, 42L, other))
        assertEquals(first, cache.find("Bolt", bolt, 42L))
        assertEquals(first, cache.find("Bolt", bolt)) // screen discovery before record id is known
        assertEquals("currency_cold_start_frozen", cache.find("Bolt", bolt, 42L)?.thresholdSource)
    }

    @Test
    fun genuinelyDifferentOfferHasDifferentKeyAndNewVerdict() {
        val cache = LiveOfferVerdictCache(nowMs = { 1L })
        cache.remember("Bolt", bolt, 10L, verdict(1L))
        val next = bolt.copy(
            restaurant = "Sushi Square",
            pickupAddresses = listOf("Vilniaus g. 47, Vilnius"),
        )
        assertNull(cache.find("Bolt", next))
        assertEquals(OfferDecisionBand.BAD,
            cache.remember("Bolt", next, 11L, verdict(1L).copy(band = OfferDecisionBand.BAD)).band)
    }

    @Test
    fun twoPersistedOfferIdsWithSameSparseBoltFingerprintStayIndependent() {
        val cache = LiveOfferVerdictCache(nowMs = { 100L })
        val first = verdict(100L)
        val next = first.copy(rateLine = "€0.30/km 👎", band = OfferDecisionBand.BAD)
        cache.remember("Bolt", bolt, 1001L, first)
        assertNull(cache.find("Bolt", bolt, 1002L))
        assertEquals(next, cache.remember("Bolt", bolt, 1002L, next))
        assertEquals(first, cache.find("Bolt", bolt, 1001L))
        assertEquals(next, cache.find("Bolt", bolt, 1002L))
    }

    @Test
    fun ttlExpiresAfterThirtyMinutes() {
        var time = 100L
        val cache = LiveOfferVerdictCache(nowMs = { time })
        cache.remember("Bolt", bolt, 77L, verdict(time))
        time += 30L * 60L * 1000L
        assertNull(cache.find("Bolt", bolt, 77L))
        assertEquals(0, cache.size())
    }

    @Test
    fun lruKeepsOnly32Entries() {
        var time = 1L
        val cache = LiveOfferVerdictCache(nowMs = { time })
        repeat(32) { i ->
            cache.remember("Bolt", bolt.copy(priceCents = 200 + i), null, verdict(time))
            time++
        }
        cache.find("Bolt", bolt.copy(priceCents = 200))
        cache.remember("Bolt", bolt.copy(priceCents = 500), null, verdict(time))
        assertEquals(32, cache.size())
        assertNull(cache.find("Bolt", bolt.copy(priceCents = 201)))
        assertTrue(cache.find("Bolt", bolt.copy(priceCents = 200)) != null)
    }

    private fun verdict(now: Long) = LiveOfferVerdictCache.Verdict(
        rateLine = "€1.18/km 👍",
        band = OfferDecisionBand.GOOD,
        routeLine = "🚶 2.0 km 🚲 2.2 km",
        walkingMeters = 2_000,
        cyclingMeters = 2_200,
        thresholdSource = "currency_cold_start_frozen",
        createdAtMs = now,
    )
}
