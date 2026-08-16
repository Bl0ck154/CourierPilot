package com.block154.courierpilot

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeliveryScreenDetailsTest {

    private val boltScreen = """
        Indre B. #NCGFZ
        Items (4)
        View
        Address
        Mindaugo g 27, Vilnius
        Instructions
        Meet at my door
        Additional note
        Front gate code: 100*2025; Staircase door: 148 + Enter
        Translate
        Apartment, flat or suite number
        148
        Floor
        2
        Call
        Chat
        Delivery issues?
        Get help
        Delivered
        Slide to confirm
    """.trimIndent()

    @Test
    fun parsesRealBoltDeliveryDetailScreenWithoutInterpretingFreeFormNote() {
        val details = DeliveryScreenDetailsExtractor.extract(boltScreen)
        assertNotNull(details)
        assertEquals("Mindaugo g 27, Vilnius", details!!.address)
        assertEquals("Indre B.", details.customerName)
        assertEquals("Meet at my door", details.instructions)
        assertEquals("Front gate code: 100*2025; Staircase door: 148 + Enter", details.additionalNote)
        assertEquals("148", details.apartment)
        assertEquals("2", details.floor)
        assertEquals(
            "Instructions: Meet at my door\n" +
                "Additional note: Front gate code: 100*2025; Staircase door: 148 + Enter\n" +
                "Apartment: 148\nFloor: 2",
            details.asDetailsText(),
        )
    }

    @Test
    fun deliveryDetailsMatchCanonicalAddressDespiteFormattingDifference() {
        val details = DeliveryScreenDetailsExtractor.forAddress(boltScreen, "Mindaugo g. 27")
        assertNotNull(details)
        assertEquals("Indre B.", details!!.customerName)
    }

    @Test
    fun richerFrameUpdatesLatestDetailsWithoutCreatingSecondObservation() {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = CourierMetaDatabase.get(context)
        val house = 800 + (System.nanoTime() % 100).toInt()
        val address = "Testų g. $house, Vilnius"
        val now = System.currentTimeMillis()

        val first = AddressMemoryResolver.saveObservation(
            context = context,
            database = database,
            address = address,
            platform = "Bolt",
            customerName = null,
            detailsText = "Address captured",
            rawText = "Address\n$address",
            evidence = AddressEvidenceSource.ACCESSIBILITY_EXPLICIT_SECTION,
            now = now,
        )
        val second = AddressMemoryResolver.saveObservation(
            context = context,
            database = database,
            address = address,
            platform = "Bolt",
            customerName = "Indre B.",
            detailsText = "Instructions: Meet at my door\nAdditional note: gate code stays as text",
            rawText = "Address\n$address\nInstructions\nMeet at my door\nAdditional note\ngate code stays as text",
            evidence = AddressEvidenceSource.ACCESSIBILITY_EXPLICIT_SECTION,
            now = now + 5_000L,
        )

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first!!.addressId, second!!.addressId)
        val saved = database.findAddressById(first.addressId)
        assertNotNull(saved)
        assertEquals("Indre B.", saved!!.latestCustomerName)
        assertTrue(saved.latestDetails.orEmpty().contains("Meet at my door"))
        assertEquals(1, database.observationsForAddress(first.addressId, limit = 20).size)
    }
}
