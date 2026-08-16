package com.block154.courierpilot

import android.content.ContentValues
import android.content.Context
import org.junit.Assert.assertEquals
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
class RealCourierScreenClassificationTest {

    private val boltPickup = """
        Order is ready for pickup
        Arrive in 7 min
        Vynoteka (Kapsų str.)
        Kapsų g. 3-43, Vilnius
        Call
        Instructions
        You will find us near the "Lidl" store.
        Translate
        Order details
        1 × Finlandia 0,5 l
        3 × Birra Moretti 0,5 l
        Report an issue
    """.trimIndent()

    private val boltCustomer = """
        Address
        Naujininkų G. 23, Vilnius
        Instructions
        Leave at my door
        Additional note
        Ring door bell and leave. Thanks Door code: 90key4899
        Translate
        Apartment, flat or suite number
        8
        Entry code
        90key4899
        Floor
        3
        Call
        Chat
        Delivery issues?
        Get help
        Age verified
        Slide to confirm
    """.trimIndent()

    private val woltPickup = """
        Pickup from
        Du Kebabai
        Žirmūnų g. 106, LT-09121 Vilnius
        ORDER READY
        Address details
        Notes
        Kioskas priešais IKI parduotuvę
        Translate
        Order details
        Indė .. #499
        1 × Kebabas lėkštėje
        Difficulties with the delivery?
        Delayed? Mark the order as late
        Cancel this delivery
        I've got the items
    """.trimIndent()

    private val woltCustomer = """
        Dropoff to
        Bella, the Story Teller
        Mindaugo gatvė 1A-9, 03108 Vilnius
        CASH ORDER
        INFO
        ITEMS
        Address details
        Building name
        Odontologijos Klinika
        Company name
        N
        Deliver to
        Office
        Floor
        1
        Notes
        Come to the underground parking where you see a big “N” on the wall. The entrance is by the sides of the building.
        Translate
        Order details
        Order delivered!
    """.trimIndent()

    @Test
    fun boltPickupIsNeverCustomerAddressMemory() {
        val details = DeliveryScreenDetailsExtractor.extractForPlatform(CourierSignals.BOLT_PACKAGE, boltPickup)
        assertNull(details)
        val decision = DeliveryAddressPersistenceGate.evaluatePlatform(
            CourierSignals.BOLT_PACKAGE,
            boltPickup,
            details,
            DeliveryEventType.PICKED_UP,
        )
        assertFalse(decision.allowed)
        assertEquals(AddressPersistenceReason.PICKUP_SCREEN, decision.reason)
    }

    @Test
    fun boltCustomerUsesOnlyExplicitAddressAndKeepsEntryCodeDetails() {
        val details = DeliveryScreenDetailsExtractor.extractForPlatform(CourierSignals.BOLT_PACKAGE, boltCustomer)
        assertNotNull(details)
        assertEquals("Naujininkų G. 23, Vilnius", details!!.address)
        assertEquals("90key4899", details.entryCode)
        assertEquals("8", details.apartment)
        assertEquals("3", details.floor)

        val decision = DeliveryAddressPersistenceGate.evaluatePlatform(
            CourierSignals.BOLT_PACKAGE,
            boltCustomer,
            details,
            null,
        )
        assertTrue(decision.allowed)

        val candidates = AddressEvidenceExtractor.fromAccessibility(
            boltCustomer,
            OfferParser.parse(boltCustomer),
            details,
        )
        assertEquals(listOf("Naujininkų G. 23, Vilnius"), candidates.map { it.raw })
    }

    @Test
    fun woltPickupIsNeverCustomerAddressMemoryEvenAfterPickedUpState() {
        val details = DeliveryScreenDetailsExtractor.extractForPlatform(CourierSignals.WOLT_PACKAGE, woltPickup)
        assertNull(details)
        val decision = DeliveryAddressPersistenceGate.evaluatePlatform(
            CourierSignals.WOLT_PACKAGE,
            woltPickup,
            details,
            DeliveryEventType.PICKED_UP,
        )
        assertFalse(decision.allowed)
        assertEquals(AddressPersistenceReason.PICKUP_SCREEN, decision.reason)
    }

    @Test
    fun woltDropoffExtractsRecipientStreetAndAddressDetails() {
        val details = DeliveryScreenDetailsExtractor.extractForPlatform(CourierSignals.WOLT_PACKAGE, woltCustomer)
        assertNotNull(details)
        assertEquals("Bella, the Story Teller", details!!.customerName)
        assertEquals("Mindaugo gatvė 1A-9, 03108 Vilnius", details.address)
        assertEquals("Odontologijos Klinika", details.buildingName)
        assertEquals("N", details.companyName)
        assertEquals("Office", details.deliverTo)
        assertEquals("1", details.floor)
        assertTrue(details.additionalNote.orEmpty().contains("underground parking"))

        val decision = DeliveryAddressPersistenceGate.evaluatePlatform(
            CourierSignals.WOLT_PACKAGE,
            woltCustomer,
            details,
            null,
        )
        assertTrue(decision.allowed)

        val candidates = AddressEvidenceExtractor.fromAccessibility(
            woltCustomer,
            OfferParser.parse(woltCustomer),
            details,
        )
        assertEquals(listOf("Mindaugo gatvė 1A-9, 03108 Vilnius"), candidates.map { it.raw })
    }

    @Test
    fun cleanupRemovesLegacyVenueAndUiCustomerPollutionButKeepsAddress() {
        val context: Context = RuntimeEnvironment.getApplication()
        val meta = CourierMetaDatabase.get(context)
        val now = System.currentTimeMillis()
        val address = "Algirdo g. ${300 + (System.nanoTime() % 80).toInt()}"
        val saved = AddressMemoryResolver.saveObservation(
            context = context,
            database = meta,
            address = address,
            platform = "Wolt",
            customerName = "Address details",
            detailsText = null,
            rawText = "Dropoff to\nAddress details\n$address",
            evidence = AddressEvidenceSource.ACCESSIBILITY_EXPLICIT_SECTION,
            now = now,
        )!!

        meta.saveAddressEntity(saved.addressId, CourierMetaDatabase.ENTITY_VENUE, "66% COMPLETED", "Wolt", now)
        meta.saveAddressEntity(saved.addressId, CourierMetaDatabase.ENTITY_VENUE, "Ekomarket (Algirdo g.)", "Wolt", now)
        meta.saveAddressEntity(saved.addressId, CourierMetaDatabase.ENTITY_VENUE, "Info hub", "Wolt", now)
        meta.saveAddressEntity(saved.addressId, CourierMetaDatabase.ENTITY_CUSTOMER, "Address details", "Wolt", now)
        meta.saveAddressEntity(saved.addressId, CourierMetaDatabase.ENTITY_CUSTOMER, "Ekomarket (Algirdo g.)", "Wolt", now)
        meta.saveAddressEntity(saved.addressId, CourierMetaDatabase.ENTITY_CUSTOMER, "Notes", "Wolt", now)

        AddressMetadataCleanup.run(context)

        assertNotNull(meta.findAddressById(saved.addressId))
        assertTrue(meta.entitiesForAddress(saved.addressId, CourierMetaDatabase.ENTITY_VENUE).isEmpty())
        assertTrue(meta.entitiesForAddress(saved.addressId, CourierMetaDatabase.ENTITY_CUSTOMER).isEmpty())
        assertNull(meta.findAddressById(saved.addressId)!!.latestCustomerName)
    }
}
