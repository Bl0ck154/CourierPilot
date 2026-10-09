package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** October 2026 Wolt customer sheet ("Deliver to" redesign). All personal data is fictional. */
class WoltRedesignedCustomerSheetTest {
    private val sheet = """
        Deliver to
        Ona Testaitė
        Call
        Message
        Kalvarijų gatvė 15A
        Vilnius, 09310
        Suite/Floor
        7
        Customer notes
        Durų kodas 1234, 7 aukštas
        See translation
        Meet in person
        Ona Testaitė, #411
        Example Noodles (Palangos g.)
        Navigate
    """.trimIndent()

    @Test fun redesignedSheetYieldsAddressNameFloorNotesAndHandover() {
        val details = DeliveryScreenDetailsExtractor.extractForPlatform(CourierSignals.WOLT_PACKAGE, sheet)
        assertNotNull(details)
        details!!
        assertEquals("Kalvarijų gatvė 15A, Vilnius, 09310", details.address)
        assertEquals("Ona Testaitė", details.customerName)
        assertEquals("7", details.floor)
        assertEquals("Durų kodas 1234, 7 aukštas", details.additionalNote)
        assertEquals("Meet in person", details.deliverTo)
        assertNotNull(DeliveryAddressNormalizer.identity(details.address!!))
    }

    @Test fun inlineLabelValuesAreReadToo() {
        val inline = sheet.replace("Suite/Floor\n7", "Suite/Floor 7")
        val details = DeliveryScreenDetailsExtractor.extractForPlatform(CourierSignals.WOLT_PACKAGE, inline)
        assertEquals("7", details?.floor)
    }

    @Test fun redesignedSheetIsACustomerSheetAndActiveTask() {
        assertTrue(DeliveryScreenDetailsExtractor.isWoltCustomerSheet(sheet))
        assertTrue(DeliveryLifecycleTracking.hasActiveTaskSurface(sheet))
        val decision = DeliveryAddressPersistenceGate.evaluatePlatform(
            CourierSignals.WOLT_PACKAGE,
            sheet,
            DeliveryScreenDetailsExtractor.extractForPlatform(CourierSignals.WOLT_PACKAGE, sheet),
            null,
        )
        assertTrue(decision.allowed)
        // The address memory (and therefore the arrival door-code reminder) is fed from this sheet.
        val evidence = AddressEvidenceExtractor.fromAccessibility(
            sheet,
            screenDetails = DeliveryScreenDetailsExtractor.extractForPlatform(CourierSignals.WOLT_PACKAGE, sheet),
        )
        assertEquals(listOf("Kalvarijų gatvė 15A, Vilnius, 09310"), evidence.map { it.raw })
    }

    @Test fun pickupSheetIsNeverStoredAsCustomerAddress() {
        val pickup = """
            Pick up from
            Example Noodles (Palangos g.)
            Palangos gatvė 4
            Vilnius, 01117
            Navigate
        """.trimIndent()
        assertFalse(DeliveryScreenDetailsExtractor.isWoltCustomerSheet(pickup))
        assertNull(DeliveryScreenDetailsExtractor.extractForPlatform(CourierSignals.WOLT_PACKAGE, pickup))
    }
}
