package com.block154.courierpilot

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AddressMemoryEntryDeletionTest {

    @Test
    fun deletingCustomerRemovesProjectionButKeepsRawObservation() {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = CourierMetaDatabase.get(context)
        val house = (System.nanoTime() and 0xfffffff).coerceAtLeast(10_000L)
        val address = "Testų g. $house, Vilnius"
        val rawText = "Customer Živilė\n$address\nFloor 4 · leave at door"
        val addressId = requireNotNull(
            database.saveAddressObservation(
                address = address,
                platform = "Wolt",
                customerName = "Živilė",
                detailsText = "Floor 4 · leave at door",
                rawText = rawText,
                now = 20_000L,
            )
        )
        database.saveAddressEntity(
            addressId = addressId,
            entityType = CourierMetaDatabase.ENTITY_CUSTOMER,
            name = "Mantas",
            platform = "Bolt",
            now = 10_000L,
        )
        database.saveAddressEntity(
            addressId = addressId,
            entityType = CourierMetaDatabase.ENTITY_CUSTOMER,
            name = "Živilė",
            platform = "Wolt",
            now = 20_000L,
        )
        database.saveAddressEntity(
            addressId = addressId,
            entityType = CourierMetaDatabase.ENTITY_CUSTOMER,
            name = "  ZIVILE  ",
            platform = "Bolt",
            now = 20_100L,
        )

        val customerKey = AddressMemoryUiProjection.canonicalName("Živilė")
        assertEquals(2, AddressMemoryEntryDeletion.deleteCustomer(database, addressId, customerKey))

        val customers = database.entitiesForAddress(addressId, CourierMetaDatabase.ENTITY_CUSTOMER, limit = 20)
        assertEquals(listOf("Mantas"), customers.map { it.name })
        assertEquals("Mantas", database.findAddressById(addressId)?.latestCustomerName)

        val observations = database.observationsForAddress(addressId, limit = 20)
        assertEquals(1, observations.size)
        assertEquals(rawText, observations.single().rawText)

        // Granular deletion is not a blacklist. Future live evidence may learn the same customer again.
        assertTrue(
            database.saveAddressEntity(
                addressId = addressId,
                entityType = CourierMetaDatabase.ENTITY_CUSTOMER,
                name = "Živilė",
                platform = "Wolt",
                now = 30_000L,
            ) != null
        )
    }

    @Test
    fun clearingLatestDeliveryInfoKeepsRawObservation() {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = CourierMetaDatabase.get(context)
        val house = (System.nanoTime() and 0xfffffff).coerceAtLeast(20_000L)
        val address = "Atminties g. $house, Vilnius"
        val rawText = "Customer\n$address\nDoor on courtyard side"
        val addressId = requireNotNull(
            database.saveAddressObservation(
                address = address,
                platform = "Wolt",
                customerName = "Customer",
                detailsText = "Door on courtyard side",
                rawText = rawText,
                now = 40_000L,
            )
        )

        assertTrue(AddressMemoryEntryDeletion.clearLatestDeliveryInfo(database, addressId))
        assertNull(database.findAddressById(addressId)?.latestDetails)

        val observations = database.observationsForAddress(addressId, limit = 20)
        assertEquals(1, observations.size)
        assertEquals(rawText, observations.single().rawText)
    }
}
