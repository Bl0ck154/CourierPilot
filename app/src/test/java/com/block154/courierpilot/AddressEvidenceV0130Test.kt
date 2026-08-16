package com.block154.courierpilot

import android.content.ContentValues
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AddressEvidenceV0130Test {

    private lateinit var context: Context
    private lateinit var database: CourierMetaDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = CourierMetaDatabase.get(context)
        CompactAddressConfirmationGate.resetForTests()
        context.getSharedPreferences("courierpilot_delivery_lifecycle", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("courierpilot_delivery_memory", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun ocrAugmentedFrameCannotCreateOrMutateAddressMemory() {
        val goodHouse = uniqueHouse(100)
        val badHouseA = uniqueHouse(200)
        val badHouseB = uniqueHouse(300)
        val goodAddress = "A. Goštauto g. $goodHouse"

        DeliveryMemory.observeScreen(
            context,
            CourierSignals.WOLT_PACKAGE,
            "Dropoff to\nTest Customer\n$goodAddress\nNotes\nLeave at door",
            ScreenTextSource.ACCESSIBILITY,
        )
        val before = findExact(goodAddress)
        assertNotNull(before)

        val combinedOcrSoup = """
            $goodAddress
            4. Goštauto $goodHouse
            ešvitrigailos $badHouseA
            hinktinės $badHouseB
        """.trimIndent()
        DeliveryMemory.observeScreen(
            context,
            CourierSignals.WOLT_PACKAGE,
            combinedOcrSoup,
            ScreenTextSource.OCR_AUGMENTED,
        )

        assertNotNull(findExact(goodAddress))
        assertNull(findExact("4. Goštauto $goodHouse"))
        assertNull(findExact("ešvitrigailos $badHouseA"))
        assertNull(findExact("hinktinės $badHouseB"))
        assertEquals(before!!.seenCount, findExact(goodAddress)!!.seenCount)
    }

    @Test
    fun woltDropoffMarkerCanCreateCompactAddressImmediately() {
        val house = uniqueHouse(400)
        val address = "Pylimo $house"

        DeliveryMemory.observeScreen(
            context,
            CourierSignals.WOLT_PACKAGE,
            "Dropoff to\nTest Customer\n$address\nNotes\nLeave at door",
            ScreenTextSource.ACCESSIBILITY,
        )

        assertNotNull(findExact(address))
    }

    @Test
    fun repeatedRestaurantMenuRowsNeverBecomeAddresses() {
        val fakeAddress = "Fanta ${uniqueHouse(450)}"
        val restaurantAddress = "Gedimino pr. ${uniqueHouse(460)}"
        val restaurantScreen = """
            Pickup from
            McDonald's
            $restaurantAddress
            Order details
            Big Mac Menu
            $fakeAddress
            I've got the items
        """.trimIndent()

        repeat(4) {
            DeliveryMemory.observeScreen(
                context,
                CourierSignals.WOLT_PACKAGE,
                restaurantScreen,
                ScreenTextSource.ACCESSIBILITY,
            )
        }

        assertNull(findExact(fakeAddress))
        assertNull(findExact(restaurantAddress))
    }

    @Test
    fun explicitCustomerAddressWinsOverItemRowsOnSameScreen() {
        val house = uniqueHouse(470)
        val fakeHouse = uniqueHouse(480)
        val address = "Mindaugo g. $house"
        val fake = "Fanta $fakeHouse"
        val screen = """
            Indre B. #NCGFZ
            Items (4)
            $fake
            Address
            $address
            Additional note
            Front gate code: 100*2025
            Apartment, flat or suite number
            148
            Floor
            2
            Delivered
            Slide to confirm
        """.trimIndent()

        repeat(3) {
            DeliveryMemory.observeScreen(context, CourierSignals.BOLT_PACKAGE, screen)
        }

        assertNotNull(findExact(address))
        assertNull(findExact(fake))
    }

    @Test
    fun clippedCompactStringsNeverCreateEvenWhenRepeated() {
        val a = "ešvitrigailos ${uniqueHouse(500)}"
        val b = "hinktinės ${uniqueHouse(600)}"
        val c = "4. Goštauto ${uniqueHouse(700)}"

        seedPickedUpLifecycle(CourierSignals.WOLT_PACKAGE)
        repeat(3) {
            DeliveryMemory.observeScreen(context, CourierSignals.WOLT_PACKAGE, a, ScreenTextSource.ACCESSIBILITY)
            DeliveryMemory.observeScreen(context, CourierSignals.WOLT_PACKAGE, b, ScreenTextSource.ACCESSIBILITY)
            DeliveryMemory.observeScreen(context, CourierSignals.WOLT_PACKAGE, c, ScreenTextSource.ACCESSIBILITY)
        }

        assertNull(findExact(a))
        assertNull(findExact(b))
        assertNull(findExact(c))
        assertFalse(DeliveryAddressNormalizer.isPlausibleNewCompactDisplay(a))
        assertFalse(DeliveryAddressNormalizer.isPlausibleNewCompactDisplay(b))
        assertFalse(DeliveryAddressNormalizer.isPlausibleNewCompactDisplay(c))
    }

    @Test
    fun customerDetailAddressLabelCanCreateCompactAddressImmediately() {
        val house = uniqueHouse(800)
        val address = "Pylimo $house"

        DeliveryMemory.observeScreen(
            context,
            CourierSignals.BOLT_PACKAGE,
            "Address\n$address\nAdditional note\nLeave at my door",
            ScreenTextSource.ACCESSIBILITY,
        )

        assertNotNull(findExact(address))
    }

    @Test
    fun bareAddressLabelWithoutCustomerContextDoesNotPersist() {
        val house = uniqueHouse(850)
        val address = "Pylimo $house"

        repeat(3) {
            DeliveryMemory.observeScreen(
                context,
                CourierSignals.BOLT_PACKAGE,
                "Address\n$address\nItems (2)",
                ScreenTextSource.ACCESSIBILITY,
            )
        }

        assertNull(findExact(address))
    }

    @Test
    fun revisionFiveRepairsScreenshotStyleLegacyVariantsFromRawText() {
        val gostaHouse = uniqueHouse(900)
        val svitrigailosHouse = uniqueHouse(1000)
        val rinktinesHouse = uniqueHouse(1100)

        val gostaId = insertLegacy(
            display = "4. Goštauto $gostaHouse",
            raw = "A. Goštauto g. $gostaHouse\n4. Goštauto $gostaHouse",
        )
        val svitrigailosId = insertLegacy(
            display = "ešvitrigailos $svitrigailosHouse",
            raw = "Švitrigailos $svitrigailosHouse\nešvitrigailos $svitrigailosHouse",
        )
        val rinktinesId = insertLegacy(
            display = "hinktinės $rinktinesHouse",
            raw = "Rinktinės $rinktinesHouse\nhinktinės $rinktinesHouse",
        )

        rerunRepair()

        assertEquals(
            DeliveryAddressNormalizer.normalize("A. Goštauto g. $gostaHouse")?.first,
            database.findAddressById(gostaId)?.buildingKey,
        )
        assertEquals("A. Goštauto g. $gostaHouse", database.findAddressById(gostaId)?.displayAddress)
        assertEquals("Švitrigailos $svitrigailosHouse", database.findAddressById(svitrigailosId)?.displayAddress)
        assertEquals("Rinktinės $rinktinesHouse", database.findAddressById(rinktinesId)?.displayAddress)
    }

    @Test
    fun revisionFiveDeletesUnrecoverableOneFrameFragmentButKeepsLegitCompactAddress() {
        val bad = "ešvitrigailos ${uniqueHouse(1200)}"
        val good = "Pylimo ${uniqueHouse(1300)}"
        val badId = insertLegacy(display = bad, raw = bad)
        val goodId = insertLegacy(display = good, raw = good)

        rerunRepair()

        assertNull(database.findAddressById(badId))
        assertNotNull(database.findAddressById(goodId))
        assertTrue(database.findAddressById(goodId)!!.displayAddress.startsWith("Pylimo "))
    }

    @Test
    fun directPersistenceRequiresEvidenceAndOcrEvidenceFailsClosed() {
        val house = uniqueHouse(1400)
        val address = "Rūdninkų gatvė $house"

        val ocr = AddressMemoryResolver.saveObservation(
            context = context,
            database = database,
            address = address,
            platform = "Wolt",
            customerName = null,
            detailsText = null,
            rawText = address,
            evidence = AddressEvidenceSource.OCR_AUGMENTED,
        )
        assertNull(ocr)

        val trusted = AddressMemoryResolver.saveObservation(
            context = context,
            database = database,
            address = address,
            platform = "Wolt",
            customerName = null,
            detailsText = null,
            rawText = address,
            evidence = AddressEvidenceSource.ACCESSIBILITY_STRICT_LINE,
        )
        assertNotNull(trusted)
    }

    private fun findExact(address: String): AddressRecord? {
        val normalized = DeliveryAddressNormalizer.normalize(address) ?: return null
        return database.searchAddresses("", limit = 200, offset = 0)
            .firstOrNull { row ->
                DeliveryAddressNormalizer.normalize(row.displayAddress)?.first == normalized.first &&
                    row.displayAddress.equals(normalized.second, ignoreCase = false)
            }
    }

    private fun insertLegacy(display: String, raw: String): Long {
        val db = database.writableDatabase
        val suffix = System.nanoTime().toString()
        val now = System.currentTimeMillis()
        val id = db.insertOrThrow(
            "addresses",
            null,
            ContentValues().apply {
                put("building_key", "legacy-$suffix-${display.hashCode()}")
                put("display_address", display)
                put("platform", "Wolt")
                put("first_seen_at", now)
                put("last_seen_at", now)
                put("seen_count", 1)
                put("latest_raw_text", raw)
            },
        )
        db.insertOrThrow(
            "address_observations",
            null,
            ContentValues().apply {
                put("address_id", id)
                put("seen_at", now)
                put("platform", "Wolt")
                put("raw_text", raw)
            },
        )
        return id
    }

    private fun rerunRepair() {
        context.getSharedPreferences("courierpilot_address_repairs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        AddressDataRepair.runIfNeeded(context)
    }

    private fun seedPickedUpLifecycle(packageName: String) {
        val prefix = packageName.replace('.', '_')
        context.getSharedPreferences("courierpilot_delivery_lifecycle", Context.MODE_PRIVATE)
            .edit()
            .putLong("${prefix}_offer", System.nanoTime().coerceAtLeast(1L))
            .putString("${prefix}_last_event", DeliveryEventType.PICKED_UP.name)
            .commit()
    }

    private fun uniqueHouse(offset: Int): Int =
        offset + 2000 + (System.nanoTime() % 70L).toInt()
}
