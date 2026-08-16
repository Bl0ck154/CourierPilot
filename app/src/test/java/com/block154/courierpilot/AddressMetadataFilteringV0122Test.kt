package com.block154.courierpilot

import android.content.ContentValues
import android.content.Context
import org.junit.Assert.assertFalse
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
class AddressMetadataFilteringV0122Test {

    @Test
    fun compactDetectorKeepsRealStreetsAndRejectsDeliveryMetadata() {
        val text = """
            O Bag/Unit 1
            Bag/Unit 1
            Apartment, 18
            Floor 2
            Door 4
            Pylimo 9
            Rūdninkų gatvė 8
        """.trimIndent()

        val detected = DeliveryAddressNormalizer.likelyAddressLines(text)

        assertTrue(detected.contains("Pylimo 9"))
        assertTrue(detected.contains("Rūdninkų gatvė 8"))
        assertFalse(detected.any { it.contains("Bag/Unit", ignoreCase = true) })
        assertFalse(detected.any { it.startsWith("Apartment", ignoreCase = true) })
        assertFalse(detected.any { it.startsWith("Floor", ignoreCase = true) })
        assertFalse(detected.any { it.startsWith("Door", ignoreCase = true) })
    }

    @Test
    fun persistenceBoundaryRejectsMetadataEvenIfAParserPassesItDirectly() {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = CourierMetaDatabase.get(context)

        assertNull(save(context, database, "O Bag/Unit 1"))
        assertNull(save(context, database, "Bag/Unit 1"))
        assertNull(save(context, database, "Apartment, 18"))
        assertNull(save(context, database, "Floor 2"))

        val house = uniqueHouse(600)
        assertNotNull(save(context, database, "Pylimo $house"))
    }

    @Test
    fun structuredBoltDetailsPersistOnlyTheAddressSectionAsABuilding() {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = CourierMetaDatabase.get(context)
        val house = uniqueHouse(700)
        val address = "Rūdninkų gatvė $house, Vilnius"
        val screen = """
            Indre B. #NCGFZ
            Items (4)
            View
            Address
            $address
            Instructions
            O Bag/Unit 1
            Additional note
            Leave at my door
            Apartment, flat or suite number
            18
            Floor
            2
            Call
            Chat
            Delivered
            Slide to confirm
        """.trimIndent()

        DeliveryMemory.observeScreen(context, CourierSignals.BOLT_PACKAGE, screen)

        val rows = database.searchAddresses("", limit = 500, offset = 0)
        assertTrue(rows.any { DeliveryAddressNormalizer.matchScore(it.displayAddress, address) >= 0.99 })
        assertFalse(rows.any { DeliveryAddressNormalizer.isRejectedAddressArtifact(it.displayAddress) })
    }

    @Test
    fun revisionFourDeletesAlreadyPollutedRowsButKeepsRealAddresses() {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = CourierMetaDatabase.get(context)
        val db = database.writableDatabase
        val suffix = System.nanoTime().toString().takeLast(8)
        val fakeKey = "legacy-bag-unit-$suffix"
        val now = System.currentTimeMillis()

        val fakeId = db.insertOrThrow(
            "addresses",
            null,
            ContentValues().apply {
                put("building_key", fakeKey)
                put("display_address", "Bag/Unit 1")
                put("platform", "Wolt")
                put("first_seen_at", now)
                put("last_seen_at", now)
                put("seen_count", 1)
            },
        )
        db.insertOrThrow(
            "address_observations",
            null,
            ContentValues().apply {
                put("address_id", fakeId)
                put("seen_at", now)
                put("platform", "Wolt")
                put("raw_text", "Bag/Unit 1")
            },
        )
        db.insertOrThrow(
            "address_entities",
            null,
            ContentValues().apply {
                put("address_id", fakeId)
                put("entity_type", CourierMetaDatabase.ENTITY_CUSTOMER)
                put("normalized_name", "fake customer $suffix")
                put("display_name", "Fake Customer $suffix")
                put("platform", "Wolt")
                put("first_seen_at", now)
                put("last_seen_at", now)
                put("seen_count", 1)
            },
        )
        db.insertOrThrow(
            "access_codes",
            null,
            ContentValues().apply {
                put("building_key", fakeKey)
                put("display_address", "Bag/Unit 1")
                put("code", "9999#$suffix")
                put("platform", "Wolt")
                put("first_seen_at", now)
                put("last_seen_at", now)
                put("seen_count", 1)
            },
        )

        val legitHouse = uniqueHouse(800)
        val legit = save(context, database, "Algirdo g. $legitHouse")
        assertNotNull(legit)

        context.getSharedPreferences("courierpilot_address_repairs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        AddressDataRepair.runIfNeeded(context)

        assertNull(database.findAddressById(fakeId))
        assertTrue(database.codesForBuilding(fakeKey).isEmpty())
        assertNotNull(database.findAddressById(legit!!.addressId))
    }

    @Test
    fun artifactClassifierMatchesReportedRowsWithoutRejectingRealCompactAddresses() {
        assertTrue(DeliveryAddressNormalizer.isRejectedAddressArtifact("O Bag/Unit 1"))
        assertTrue(DeliveryAddressNormalizer.isRejectedAddressArtifact("Bag/Unit 1"))
        assertTrue(DeliveryAddressNormalizer.isRejectedAddressArtifact("Apartment, 18"))
        assertTrue(DeliveryAddressNormalizer.isRejectedAddressArtifact("Floor 2"))
        assertFalse(DeliveryAddressNormalizer.isRejectedAddressArtifact("Pylimo 9"))
        assertFalse(DeliveryAddressNormalizer.isRejectedAddressArtifact("Rūdninkų gatvė 8"))
    }

    private fun save(
        context: Context,
        database: CourierMetaDatabase,
        address: String,
    ): SmartAddressSaveResult? = AddressMemoryResolver.saveObservation(
        context = context,
        database = database,
        address = address,
        platform = "Wolt",
        customerName = null,
        detailsText = null,
        rawText = address,
    )

    private fun uniqueHouse(offset: Int): Int =
        offset + 100 + (System.nanoTime() % 80L).toInt()
}
