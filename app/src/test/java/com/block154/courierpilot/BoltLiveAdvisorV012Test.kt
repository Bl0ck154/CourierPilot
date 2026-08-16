package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoltLiveAdvisorV012Test {
    private val parsed = OfferParser.parse(
        """
        Bolt
        Hesburger (Vokiečių str.)
        Vokiečių g. 12, Vilnius, 01130 Vilniaus m. sav.
        ~4 min
        ~7 min
        11 min, 2,49 €
        Decline
        Accept
        """.trimIndent()
    )

    @Test
    fun boltOfferKeepsPickupAndEtaWhenPlatformDoesNotExposeDistance() {
        assertEquals(249, parsed.priceCents)
        assertNull(parsed.distanceMeters)
        assertEquals(11, parsed.estimatedMinutesMin)
        assertEquals(11, parsed.estimatedMinutesMax)
        assertTrue(parsed.pickupAddresses.any { it.contains("Vokiečių g. 12") })
    }

    @Test
    fun semanticClassifierRejectsOrdinaryCardTextOutsideMap() {
        assertNull(BoltMarkerSemanticExtractor.classifySemantic("Hesburger (Vokiečių str.)", false, parsed))
    }

    @Test
    fun semanticClassifierAcceptsExplicitCurrentPickupAndCustomerMarkersInsideMap() {
        assertEquals(
            BoltMarkerKind.CURRENT_LOCATION,
            BoltMarkerSemanticExtractor.classifySemantic("Current location marker", true, parsed)?.first,
        )
        assertEquals(
            BoltMarkerKind.PICKUP,
            BoltMarkerSemanticExtractor.classifySemantic("Hesburger map marker", true, parsed)?.first,
        )
        assertEquals(
            BoltMarkerKind.DROPOFF,
            BoltMarkerSemanticExtractor.classifySemantic("Customer marker", true, parsed)?.first,
        )
    }
}
