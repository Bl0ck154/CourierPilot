package com.block154.courierpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessCodeHintPolicyTest {

    @Test
    fun apartmentNumberIsNeverLearnedAsDoorCode() {
        val screen = """
            Address
            Žirmūnų g. 23-145, Vilnius
            Apartment, flat or suite number
            145
            Floor
            3
        """.trimIndent()

        assertFalse(AccessCodeHintPolicy.shouldLearnCandidate(screen, "145"))
        assertTrue(AccessCodeHintPolicy.shouldLearnCandidate(screen, "1234#"))
    }

    @Test
    fun historicalCodeAlreadyVisibleOnCurrentOrderIsNotUsefulHint() {
        val screen = """
            Address
            Žirmūnų g. 23-145, Vilnius
            Apartment, flat or suite number
            145
            Additional note
            Use 90key4899 at the intercom
        """.trimIndent()

        assertTrue(AccessCodeHintPolicy.isAlreadyVisible(screen, "145"))
        assertTrue(AccessCodeHintPolicy.isAlreadyVisible(screen, "90key4899"))
        assertFalse(AccessCodeHintPolicy.isAlreadyVisible(screen, "7711#"))
    }

    @Test
    fun unusualHumanWrittenDoorCodeStillCountsAsCurrentOrderInformation() {
        val screen = """
            Delivery instructions
            Please use door code star 24 hash, then ring apartment 8
        """.trimIndent()

        assertTrue(AccessCodeHintPolicy.screenContainsAccessCodeInfo(screen))
    }

    @Test
    fun emptyEntryCodeFieldDoesNotSuppressHistoricalHint() {
        val screen = """
            Address
            Naujininkų G. 23, Vilnius
            Entry code
            Floor
            3
            Call
        """.trimIndent()

        assertFalse(AccessCodeHintPolicy.screenContainsAccessCodeInfo(screen))
    }

    @Test
    fun entryCodeOnFollowingLineCountsAsCurrentOrderInformation() {
        val screen = """
            Address
            Naujininkų G. 23, Vilnius
            Entry code
            90key4899
            Floor
            3
        """.trimIndent()

        assertTrue(AccessCodeHintPolicy.screenContainsAccessCodeInfo(screen))
    }
}
