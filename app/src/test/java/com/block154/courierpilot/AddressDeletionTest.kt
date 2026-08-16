package com.block154.courierpilot

import android.content.Context
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AddressDeletionTest {

    @Test
    fun deleteRemovesAddressObservationsEntitiesCodesAndStaleCaches() {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = CourierMetaDatabase.get(context)
        val house = 5000 + (System.nanoTime() % 500L).toInt()
        val raw = "Testų g. $house"

        val saved = AddressMemoryResolver.saveObservation(
            context = context,
            database = database,
            address = raw,
            platform = "Bolt",
            customerName = "Test Customer",
            detailsText = "Additional note: test",
            rawText = "Address\n$raw\nAdditional note\ntest",
            evidence = AddressEvidenceSource.ACCESSIBILITY_EXPLICIT_SECTION,
        )
        assertNotNull(saved)
        val address = database.findAddressById(saved!!.addressId)
        assertNotNull(address)

        database.saveAddressEntity(
            addressId = address!!.id,
            entityType = CourierMetaDatabase.ENTITY_CUSTOMER,
            name = "Test Customer",
            platform = "Bolt",
        )
        database.saveAccessCode(
            AccessCodeObservation(
                buildingKey = address.buildingKey,
                displayAddress = address.displayAddress,
                code = "1234",
            ),
            platform = "Bolt",
        )

        context.getSharedPreferences("courierpilot_address_aliases_v2", Context.MODE_PRIVATE)
            .edit().putString("stale", "value").commit()
        context.getSharedPreferences("courierpilot_delivery_memory", Context.MODE_PRIVATE)
            .edit().putString("stale", "value").commit()

        assertTrue(AddressDeletion.delete(context, database, address))

        assertNull(database.findAddressById(address.id))
        assertTrue(database.observationsForAddress(address.id).isEmpty())
        assertTrue(database.entitiesForAddress(address.id, CourierMetaDatabase.ENTITY_CUSTOMER).isEmpty())
        assertTrue(database.codesForBuilding(address.buildingKey).isEmpty())
        assertTrue(
            context.getSharedPreferences("courierpilot_address_aliases_v2", Context.MODE_PRIVATE).all.isEmpty()
        )
        assertTrue(
            context.getSharedPreferences("courierpilot_delivery_memory", Context.MODE_PRIVATE).all.isEmpty()
        )
    }
}
