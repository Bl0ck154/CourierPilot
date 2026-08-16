package com.block154.courierpilot

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AddressIdentityV0114Test {

    @Test
    fun postcodeStreetMarkerAndLithuanianDiacriticsShareOneBuilding() {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = CourierMetaDatabase.get(context)
        val house = uniqueHouse(10)

        val withPostcode = AddressMemoryResolver.saveObservation(
            context = context,
            database = database,
            address = "Stuokos Gucevičiaus g. $house, LT01122 Vilnius",
            platform = "Wolt",
            customerName = null,
            detailsText = "Pickup",
            rawText = "first",
        )!!
        val compactAscii = AddressMemoryResolver.saveObservation(
            context = context,
            database = database,
            address = "Stuokos Guceviciaus $house",
            platform = "Wolt",
            customerName = null,
            detailsText = "Pickup",
            rawText = "second",
        )!!

        assertEquals(withPostcode.addressId, compactAscii.addressId)
        assertTrue(compactAscii.displayAddress.contains(house.toString()))
    }

    @Test
    fun missingStreetMarkerAndOneCharacterTypoUseUniqueLocalCandidate() {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = CourierMetaDatabase.get(context)
        val house = uniqueHouse(110)

        val official = AddressMemoryResolver.saveObservation(
            context, database, "Vokiečių g. $house, Vilnius", "Wolt", null, null, "first"
        )!!
        val noMarker = AddressMemoryResolver.saveObservation(
            context, database, "Vokieciu $house", "Wolt", null, null, "second"
        )!!
        val smallTypo = AddressMemoryResolver.saveObservation(
            context, database, "Vokeciu $house", "Wolt", null, null, "third"
        )!!

        assertEquals(official.addressId, noMarker.addressId)
        assertEquals(official.addressId, smallTypo.addressId)
    }

    @Test
    fun explicitDifferentStreetTypesDoNotMergeLocally() {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = CourierMetaDatabase.get(context)
        val house = uniqueHouse(210)

        val street = AddressMemoryResolver.saveObservation(
            context, database, "Savanorių g. $house, Vilnius", "Wolt", null, null, "street"
        )!!
        val avenue = AddressMemoryResolver.saveObservation(
            context, database, "Savanorių pr. $house, Vilnius", "Wolt", null, null, "avenue"
        )!!

        assertNotEquals(street.addressId, avenue.addressId)
        assertEquals(0.0, DeliveryAddressNormalizer.matchScore("Savanorių g. $house", "Savanorių pr. $house"), 0.0)
    }

    @Test
    fun translatedDifferentScriptIsLeftForGeocoderFallback() {
        val house = uniqueHouse(310)
        assertEquals(
            0.0,
            DeliveryAddressNormalizer.matchScore("Vokiečių g. $house", "Немецкая улица $house"),
            0.0,
        )
    }

    @Test
    fun compactAddressDetectorAcceptsAddressWithoutStreetMarker() {
        val house = uniqueHouse(410)
        val detected = DeliveryAddressNormalizer.likelyAddressLines(
            "Customer\nVokiečių $house\n€5.96\nAccept"
        )
        assertTrue(detected.contains("Vokiečių $house"))
    }

    private fun uniqueHouse(offset: Int): Int =
        500 + offset + (System.nanoTime() % 70L).toInt()
}
