package com.block154.courierpilot

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfferCaptureDedupeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("courier_offer_capture", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("courierpilot_screen_offer_dedupe", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun capturedNotificationCannotRearmUntilNotificationIsRemoved() {
        val key = "0|com.wolt.courierapp|42|null|1000"

        assertEquals(
            ArmResult.ARMED,
            OfferState.arm(context, CourierSignals.WOLT_PACKAGE, "Wolt Partner", key),
        )
        OfferState.clear(context)

        assertEquals(
            ArmResult.DUPLICATE_UPDATE,
            OfferState.arm(context, CourierSignals.WOLT_PACKAGE, "Wolt Partner", key),
        )

        OfferState.releaseCapturedNotification(context, CourierSignals.WOLT_PACKAGE, key)
        assertEquals(
            ArmResult.ARMED,
            OfferState.arm(context, CourierSignals.WOLT_PACKAGE, "Wolt Partner", key),
        )
    }

    @Test
    fun changingAccessibilityDetailsKeepSameBurstIdentity() {
        val first = OfferParser.parse(
            """
            €6.42
            2 deliveries from
            Guacamole Mexican Grill (Baltupiai), Venue Two
            Route distance
            10.4 km
            Estimated
            25 - 38 min
            Timeline
            Guacamole Mexican Grill (Baltupiai)
            Kalvarijų g. 200, Vilnius
            Venue Two
            Didlaukio g. 80, Vilnius
            Accept
            """.trimIndent()
        )
        val richerFrame = OfferParser.parse(
            """
            €6.42
            2 deliveries from
            Guacamole Mexican Grill (Baltupiai), Venue Two
            Route distance
            10.4 km
            Estimated
            24 - 37 min
            Timeline
            Guacamole Mexican Grill (Baltupiai)
            Kalvarijų g. 200, Vilnius
            Venue Two
            Didlaukio g. 80, Vilnius
            Customer A.
            Žirmūnų g. 54, Vilnius
            Customer B.
            Laisvės pr. 71, Vilnius
            Loading
            Accept
            """.trimIndent()
        )

        val exactFirst = CourierSignals.offerFingerprint(CourierSignals.WOLT_PACKAGE, first, "frame one")
        val exactSecond = CourierSignals.offerFingerprint(CourierSignals.WOLT_PACKAGE, richerFrame, "frame two")
        assertNotEquals(exactFirst, exactSecond)

        assertEquals(
            OfferDedupeIdentity.burstFingerprint(CourierSignals.WOLT_PACKAGE, first),
            OfferDedupeIdentity.burstFingerprint(CourierSignals.WOLT_PACKAGE, richerFrame),
        )
    }

    @Test
    fun recentlyCapturedScreenCannotRearmWhenOnlyDetailedRouteTextChanges() {
        val firstText = """
            €6.42
            2 deliveries from
            Guacamole Mexican Grill (Baltupiai), Venue Two
            Route distance
            10.4 km
            Timeline
            Guacamole Mexican Grill (Baltupiai)
            Kalvarijų g. 200, Vilnius
            Venue Two
            Didlaukio g. 80, Vilnius
            Accept
        """.trimIndent()
        val richerText = firstText.replace(
            "Accept",
            "Customer A.\nŽirmūnų g. 54, Vilnius\nCustomer B.\nLaisvės pr. 71, Vilnius\nAccept",
        )

        OfferState.saveUiText(context, firstText)
        val firstFingerprint = CourierSignals.offerFingerprint(CourierSignals.WOLT_PACKAGE, firstText)
        assertEquals(
            ArmResult.ARMED,
            OfferState.arm(
                context,
                CourierSignals.WOLT_PACKAGE,
                "Wolt Partner",
                "screen:$firstFingerprint",
            ),
        )
        OfferState.clear(context)

        OfferState.saveUiText(context, richerText)
        val richerFingerprint = CourierSignals.offerFingerprint(CourierSignals.WOLT_PACKAGE, richerText)
        assertNotEquals(firstFingerprint, richerFingerprint)
        assertEquals(
            ArmResult.DUPLICATE_UPDATE,
            OfferState.arm(
                context,
                CourierSignals.WOLT_PACKAGE,
                "Wolt Partner",
                "screen:$richerFingerprint",
            ),
        )
    }

    @Test
    fun persistenceGuardRecognizesRicherDuplicateButKeepsDifferentOffer() {
        val base = OfferRecord(
            capturedAt = 1_000_000L,
            platform = "Wolt",
            packageName = CourierSignals.WOLT_PACKAGE,
            priceCents = 642,
            distanceMeters = 10_400,
            restaurant = "Guacamole Mexican Grill (Baltupiai)",
            screenshotUri = "content://one",
            screenshotFilename = "one.png",
            rawText = "first",
            merchantNames = listOf("Guacamole Mexican Grill (Baltupiai)"),
            deliveryCount = 1,
        )
        val richerDuplicate = base.copy(
            id = 2L,
            capturedAt = base.capturedAt + 70_000L,
            rawText = "richer",
            customerNames = listOf("Customer A."),
            dropoffAddresses = listOf("Vokiečių g. 1-36, Vilnius"),
        )
        val differentOffer = base.copy(
            id = 3L,
            capturedAt = base.capturedAt + 80_000L,
            distanceMeters = 7_100,
            restaurant = "Different Venue",
            merchantNames = listOf("Different Venue"),
        )

        assertEquals(true, OfferDedupeIdentity.isSameLiveOffer(base, richerDuplicate))
        assertEquals(false, OfferDedupeIdentity.isSameLiveOffer(base, differentOffer))
    }

    @Test
    fun sparseNotificationAndRichScreenAreOneOfferInsideShortWindow() {
        val sparse = OfferRecord(
            capturedAt = 2_000_000L,
            platform = "Bolt",
            packageName = CourierSignals.BOLT_PACKAGE,
            priceCents = 530,
            distanceMeters = null,
            restaurant = null,
            screenshotUri = "content://sparse",
            screenshotFilename = "sparse.png",
            rawText = "€5.30",
        )
        val rich = sparse.copy(
            capturedAt = sparse.capturedAt + 20_000L,
            distanceMeters = 3_200,
            restaurant = "Example Pizza",
            merchantNames = listOf("Example Pizza"),
            pickupAddresses = listOf("Gedimino pr. 10, Vilnius"),
            dropoffAddresses = listOf("Vokiečių g. 1-36, Vilnius"),
        )

        assertEquals(true, OfferDedupeIdentity.isSameLiveOffer(sparse, rich))
    }

    @Test
    fun strongSameRouteCanDeduplicateBeyondThreeMinutes() {
        val first = OfferRecord(
            capturedAt = 3_000_000L,
            platform = "Wolt",
            packageName = CourierSignals.WOLT_PACKAGE,
            priceCents = 710,
            distanceMeters = 4_800,
            restaurant = "Example Sushi",
            screenshotUri = "content://first",
            screenshotFilename = "first.png",
            rawText = "first",
            merchantNames = listOf("Example Sushi"),
            pickupAddresses = listOf("Pylimo g. 20, Vilnius"),
            dropoffAddresses = listOf("Vokiečių g. 1–36, Vilnius"),
            deliveryCount = 1,
        )
        val duplicate = first.copy(
            capturedAt = first.capturedAt + 5L * 60L * 1000L,
            screenshotUri = "content://second",
            screenshotFilename = "second.png",
            rawText = "richer frame",
            dropoffAddresses = listOf("Vokiečių g. 1, Vilnius"),
        )
        val genuinelyDifferent = duplicate.copy(
            capturedAt = first.capturedAt + 6L * 60L * 1000L,
            dropoffAddresses = listOf("Vokiečių g. 9, Vilnius"),
        )

        assertEquals(true, OfferDedupeIdentity.isSameLiveOffer(first, duplicate))
        assertEquals(false, OfferDedupeIdentity.isSameLiveOffer(first, genuinelyDifferent))
    }

    @Test
    fun shortBurstCanIgnorePickupMisclassificationWhenDropoffMatches() {
        val first = OfferRecord(
            capturedAt = 4_000_000L,
            platform = "Wolt",
            packageName = CourierSignals.WOLT_PACKAGE,
            priceCents = 596,
            distanceMeters = 9_900,
            restaurant = "OSH 2 by Ugruzina",
            screenshotUri = "content://first",
            screenshotFilename = "first.png",
            rawText = "first",
            merchantNames = listOf("OSH 2 by Ugruzina"),
            pickupAddresses = listOf("Stuokos Gucevičiaus g. 7, LT01122 Vilnius"),
            dropoffAddresses = listOf("Loop Hotel Vilnius, 02189 Vilnius"),
            deliveryCount = 1,
        )
        val richerSameOffer = first.copy(
            capturedAt = first.capturedAt + 35_000L,
            pickupAddresses = listOf("Different parser artifact g. 9, Vilnius"),
            screenshotUri = "content://second",
            screenshotFilename = "second.png",
        )
        val realDifferentOffer = first.copy(
            capturedAt = first.capturedAt + 40_000L,
            dropoffAddresses = listOf("Vokiečių g. 24, Vilnius"),
            screenshotUri = "content://third",
            screenshotFilename = "third.png",
        )

        assertEquals(true, OfferDedupeIdentity.isSameLiveOffer(first, richerSameOffer))
        assertEquals(false, OfferDedupeIdentity.isSameLiveOffer(first, realDifferentOffer))
    }

    @Test
    fun repeatedCanonicalStopsCollapseToOnePhysicalPickupAndDropoff() {
        val stored = OfferRecord(
            capturedAt = 5_000_000L,
            platform = "Wolt",
            packageName = CourierSignals.WOLT_PACKAGE,
            priceCents = 596,
            distanceMeters = 9_900,
            restaurant = "OSH 2 by Ugruzina",
            screenshotUri = "content://offer",
            screenshotFilename = "offer.png",
            rawText = "",
            merchantNames = listOf("OSH 2 by Ugruzina"),
            pickupAddresses = listOf(
                "Stuokos Gucevičiaus g. 7, LT01122 Vilnius",
                "Stuokos Gucevičiaus g. 7,\u00A0LT01122 Vilnius",
            ),
            customerNames = listOf("javaria m.", "Customer"),
            dropoffAddresses = listOf(
                "Loop Hotel Vilnius, 02189 Vilnius",
                "Loop Hotel Vilnius,\u00A002189 Vilnius",
            ),
            deliveryCount = 2,
        )

        val repaired = stored.withCurrentParsedStructure()

        assertEquals(1, repaired.pickupAddresses.size)
        assertEquals(1, repaired.dropoffAddresses.size)
        assertEquals(listOf("javaria m."), repaired.customerNames)
        assertEquals(1, repaired.deliveryCount)
    }

    @Test
    fun postalCodeVariantsNormalizeToOneBuilding() {
        val canonical = DeliveryAddressNormalizer.normalize("Stuokos Gucevičiaus g. 7")

        assertEquals(canonical, DeliveryAddressNormalizer.normalize("Stuokos Gucevičiaus g. 7, LT01122 Vilnius"))
        assertEquals(canonical, DeliveryAddressNormalizer.normalize("Stuokos Gucevičiaus g. 7, LT-01122 Vilnius"))
        assertEquals(canonical, DeliveryAddressNormalizer.normalize("Stuokos Gucevičiaus g. 7, 01122 Vilnius"))
        assertEquals(
            DeliveryAddressNormalizer.normalize("M. Mironaitės gatvė 14"),
            DeliveryAddressNormalizer.normalize("M. Mironaitės gatvė 14, 04234 Vilnius"),
        )
    }

    @Test
    fun apartmentSuffixWithUnicodeDashNormalizesToBuilding() {
        val normalized = DeliveryAddressNormalizer.normalize("Vokiečių g. 1–36, Vilnius")

        assertEquals("vokieciu g 1", normalized?.first)
        assertEquals("Vokiečių g. 1", normalized?.second)
        assertEquals(
            normalized,
            DeliveryAddressNormalizer.normalize("Vokiečių g. 1, Vilnius"),
        )
    }
}
