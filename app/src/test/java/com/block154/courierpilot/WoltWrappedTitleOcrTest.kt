package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Test

/** Wrapped Wolt venue title + OCR `LTO1117` produced a fake venue `g.)` and a duplicate pickup. */
class WoltWrappedTitleOcrTest {
    private val ocr = """
        €9.96
        4 stops (15.4 km) • 29–42 min
        Eat More Chinese & Shimai Sushi (Palangos
        g.)
        Palangos g. 2, Vilnius, LTO1117
        Azerai (Geležinkelio g.)
        Geležinkelio g. 8a, Vilnius, LT-02100
        Multiple drop-offs (2 stops)
        Accept
    """.trimIndent()

    private val merchants = listOf("Eat More Chinese & Shimai Sushi (Palangos g.)", "Azerai (Geležinkelio g.)")
    private val pickups = listOf("Palangos g. 2, Vilnius, LT01117", "Geležinkelio g. 8a, Vilnius, LT-02100")

    @Test
    fun wrappedTitleTailJoinsTheVenueName() {
        val parsed = OfferParser.parse(ocr)
        assertEquals(merchants, parsed.merchantNames)
        assertEquals(pickups, parsed.pickupAddresses)
    }

    @Test
    fun storedRowWithFakeVenueAndOcrDuplicateIsRepairedOnRead() {
        val stored = OfferRecord(
            capturedAt = 1L,
            platform = "Wolt",
            packageName = CourierSignals.WOLT_PACKAGE,
            priceCents = 996,
            distanceMeters = 15_400,
            restaurant = "Eat More Chinese & Shimai Sushi (Palangos g.), Azerai (Geležinkelio g.), g.)",
            screenshotUri = "",
            screenshotFilename = "",
            rawText = ocr,
            merchantNames = merchants + "g.)",
            pickupAddresses = listOf(
                "Palangos g. 2, Vilnius, LTO1117",
                "Geležinkelio g. 8a, Vilnius, LT-02100",
                "Palangos g. 2, Vilnius, LT01117",
            ),
        )
        val repaired = stored.withCurrentParsedStructure()
        assertEquals(merchants, repaired.merchantNames)
        assertEquals(pickups, repaired.pickupAddresses)
    }

    @Test
    fun postcodeRepairTouchesOnlyLithuanianPostcodes() {
        assertEquals("LT01117", OcrPostcodeRepair.repair("LTO1117"))
        assertEquals("LT-02100", OcrPostcodeRepair.repair("LT-O2100"))
        assertEquals("LT-O21OO", OcrPostcodeRepair.repair("LT-O21OO"))
        assertEquals("LTOOOOO", OcrPostcodeRepair.repair("LTOOOOO"))
        assertEquals("Vilnius", OcrPostcodeRepair.repair("Vilnius"))
    }
}
