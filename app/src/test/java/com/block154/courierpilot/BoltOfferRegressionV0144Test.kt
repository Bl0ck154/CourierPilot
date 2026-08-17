package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Test

class BoltOfferRegressionV0144Test {

    @Test
    fun mapLabelsDoNotReplaceNoForksMerchant() {
        val parsed = OfferParser.parse(
            """
            Decline
            OLD TOWN
            No Forks Mexican Grill (Vokiečių str.)
            Railway Park
            Vokiečių g. 9, Vilnius
            ~11 min
            Vilnius
            ~8 min
            19 min, 3,28 €
            """.trimIndent()
        )

        assertEquals("No Forks Mexican Grill (Vokiečių str.)", parsed.restaurant)
        assertEquals(listOf("Vokiečių g. 9, Vilnius"), parsed.pickupAddresses)
    }

    @Test
    fun hiddenAndInlineOcrGlyphsAreRemovedFromLithuanianAddress() {
        val badAddress = "Vokieči" + '\u200B' + "|ų g. 9, Vilnius"
        val parsed = OfferParser.parse(
            listOf(
                "Decline",
                "No Forks Mexican Grill (Vokiečių str.)",
                badAddress,
                "~11 min",
                "~8 min",
                "19 min, 3,28 €",
            ).joinToString("\n")
        )

        assertEquals(listOf("Vokiečių g. 9, Vilnius"), parsed.pickupAddresses)
    }
}
