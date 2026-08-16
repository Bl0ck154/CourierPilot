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

        // Simulate the successful-capture path. clear() now leaves a notification tombstone.
        OfferState.clear(context)

        assertEquals(
            ArmResult.DUPLICATE_UPDATE,
            OfferState.arm(context, CourierSignals.WOLT_PACKAGE, "Wolt Partner", key),
        )

        // Android removal ends that notification lifetime, so a later genuine offer may reuse it.
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

}
