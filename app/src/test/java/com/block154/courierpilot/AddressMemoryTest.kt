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
class AddressMemoryTest {

    @Test
    fun apartmentAndStreetAliasShareBuildingAndKeepPlatformEntities() {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = CourierMetaDatabase.get(context)
        val suffix = System.currentTimeMillis().toString().takeLast(5)
        val house = (700 + suffix.takeLast(2).toInt()).coerceAtMost(999)

        val firstId = database.saveAddressObservation(
            address = "Testų g. $house-36, Vilnius",
            platform = "Wolt",
            customerName = "Софья C.",
            detailsText = "Drop-off",
            rawText = "Customer\nTestų g. $house-36, Vilnius",
        )
        val aliasId = database.saveAddressObservation(
            address = "Testų gatvė $house, Vilnius",
            platform = "Bolt",
            customerName = null,
            detailsText = "Pickup",
            rawText = "Venue\nTestų gatvė $house, Vilnius",
        )

        assertNotNull(firstId)
        assertEquals(firstId, aliasId)
        database.saveAddressEntity(firstId!!, CourierMetaDatabase.ENTITY_CUSTOMER, "Софья C.", "Wolt")
        database.saveAddressEntity(firstId, CourierMetaDatabase.ENTITY_VENUE, "Test Venue", "Bolt")

        val customers = database.entitiesForAddress(firstId, CourierMetaDatabase.ENTITY_CUSTOMER)
        val venues = database.entitiesForAddress(firstId, CourierMetaDatabase.ENTITY_VENUE)
        assertTrue(customers.any { it.name == "Софья C." && it.platform == "Wolt" })
        assertTrue(venues.any { it.name == "Test Venue" && it.platform == "Bolt" })
    }
}
