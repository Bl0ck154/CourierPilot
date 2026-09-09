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
class AddressRawCaptureTest {

    @Test
    fun richerScreenInsideDedupeWindowIsPreservedWhileExactRepeatIsSkipped() {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = CourierMetaDatabase.get(context)
        val house = 500 + (System.nanoTime() % 300).toInt()
        val address = "Snapshotų g. $house, Vilnius"
        val start = 1_000_000L

        val firstRaw = """
            Address
            $address
            Apartment, flat or suite number
            145
        """.trimIndent()
        val richerRaw = """
            Address
            $address
            Instructions
            Leave at my door
            Apartment, flat or suite number
            145
            Additional note
            Ring the left bell
        """.trimIndent()

        val first = AddressMemoryResolver.saveObservation(
            context = context,
            database = database,
            address = address,
            platform = "Bolt",
            customerName = null,
            detailsText = "Apartment: 145",
            rawText = firstRaw,
            evidence = AddressEvidenceSource.ACCESSIBILITY_EXPLICIT_SECTION,
            now = start,
        )
        assertNotNull(first)

        AddressMemoryResolver.saveObservation(
            context = context,
            database = database,
            address = address,
            platform = "Bolt",
            customerName = null,
            detailsText = "Instructions: Leave at my door\nAdditional note: Ring the left bell\nApartment: 145",
            rawText = richerRaw,
            evidence = AddressEvidenceSource.ACCESSIBILITY_EXPLICIT_SECTION,
            now = start + 5_000L,
        )
        AddressMemoryResolver.saveObservation(
            context = context,
            database = database,
            address = address,
            platform = "Bolt",
            customerName = null,
            detailsText = "Instructions: Leave at my door\nAdditional note: Ring the left bell\nApartment: 145",
            rawText = richerRaw,
            evidence = AddressEvidenceSource.ACCESSIBILITY_EXPLICIT_SECTION,
            now = start + 10_000L,
        )

        val observations = database.observationsForAddress(first!!.addressId, limit = 10)
        assertEquals(2, observations.size)
        assertTrue(observations.any { it.rawText == firstRaw })
        assertTrue(observations.any { it.rawText == richerRaw })
    }
}
