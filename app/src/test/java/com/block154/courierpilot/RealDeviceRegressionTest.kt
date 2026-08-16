package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RealDeviceRegressionTest {

    @Test
    fun recognizesTrustedBoltAndWoltPresenceSignals() {
        // This real Bolt foreground-service notification only proves the app is running. It can
        // remain visible while the courier account is offline, so it must never start work time.
        assertEquals(
            PresenceSignal.UNKNOWN,
            CourierSignals.detectPresence(
                "Bolt Courier app is running\nWe keep you active while app is in background"
            ),
        )
        assertEquals(
            PresenceSignal.ONLINE,
            CourierSignals.detectPresence("Bolt Courier\nWaiting for orders"),
        )
        assertEquals(
            PresenceSignal.ONLINE,
            CourierSignals.detectPresence("Wolt Partner\nOn duty"),
        )
        assertEquals(
            PresenceSignal.OFFLINE,
            CourierSignals.detectPresence("Wolt Partner\nOff duty"),
        )
    }

    @Test
    fun previousMerchantNameCannotLeakIntoNextCustomerAddress() {
        val parsed = OfferParser.parse(
            """
            €6.98
            Expected earnings for the full delivery
            2 deliveries from
            POP IT Sushi (Dominikonų g.), Gan Bei City (G9)
            Route distance
            12.4 km
            Estimated
            33 - 46 min
            Timeline
            POP IT Sushi (Dominikonų g.)
            3 min
            Dominikonų g. 6, LT01131 Vilnius
            Gan Bei City (G9)
            8 min
            Gedimino pr. 9, LT 01105 Vilnius
            Tomas P.
            21 min
            J. Savickio gatvė 21/11, 01108 Vilnius
            Inesa M.
            38 min
            Laisvės prospektas 71a, 07189 Vilnius
            Accept
            """.trimIndent()
        )

        assertEquals(
            listOf("POP IT Sushi (Dominikonų g.)", "Gan Bei City (G9)"),
            parsed.merchantNames,
        )
        assertEquals(
            listOf("Dominikonų g. 6, LT01131 Vilnius", "Gedimino pr. 9, LT 01105 Vilnius"),
            parsed.pickupAddresses,
        )
        assertEquals(listOf("Tomas P.", "Inesa M."), parsed.customerNames)
        assertEquals(
            listOf(
                "J. Savickio gatvė 21/11, 01108 Vilnius",
                "Laisvės prospektas 71a, 07189 Vilnius",
            ),
            parsed.dropoffAddresses,
        )
        assertEquals(2, parsed.deliveryCount)
    }

    @Test
    fun supportsFourDeliveriesWithoutTreatingDeliveryCountAsPickupCount() {
        val parsed = OfferParser.parse(
            """
            €12.40
            Expected earnings for the full delivery
            4 deliveries from
            Venue One, Venue Two
            Route distance
            16.2 km
            Estimated
            45 - 63 min
            Timeline
            Venue One
            2 min
            Gedimino pr. 1, LT-01103 Vilnius
            Venue Two
            6 min
            Vilniaus g. 10, LT-01119 Vilnius
            Customer A.
            17 min
            Žirmūnų g. 10, Vilnius
            Customer B.
            28 min
            Kalvarijų g. 50, Vilnius
            Customer C.
            39 min
            Naugarduko g. 20, Vilnius
            Customer D.
            52 min
            Laisvės pr. 80, Vilnius
            Accept
            """.trimIndent()
        )

        assertEquals(2, parsed.merchantNames.size)
        assertEquals(2, parsed.pickupAddresses.size)
        assertEquals(4, parsed.customerNames.size)
        assertEquals(4, parsed.dropoffAddresses.size)
        assertEquals(4, parsed.deliveryCount)
    }

    @Test
    fun semanticFingerprintIgnoresDynamicEtaAndUiNoise() {
        val first = """
            €6.98
            Expected earnings for the full delivery
            2 deliveries from
            POP IT Sushi (Dominikonų g.), Gan Bei City (G9)
            Route distance
            12.4 km
            Estimated
            33 - 46 min
            Timeline
            POP IT Sushi (Dominikonų g.)
            Dominikonų g. 6, LT01131 Vilnius
            Gan Bei City (G9)
            Gedimino pr. 9, LT 01105 Vilnius
            Tomas P.
            J. Savickio gatvė 21/11, 01108 Vilnius
            Inesa M.
            Laisvės prospektas 71a, 07189 Vilnius
            Accept
        """.trimIndent()
        val sameOfferLater = first
            .replace("33 - 46 min", "32 - 45 min")
            .replace("Accept", "Loading\nAccept")
        val differentRoute = sameOfferLater.replace(
            "Laisvės prospektas 71a, 07189 Vilnius",
            "Laisvės prospektas 99, 07189 Vilnius",
        )

        val firstId = CourierSignals.offerFingerprint(CourierSignals.WOLT_PACKAGE, first)
        assertEquals(
            firstId,
            CourierSignals.offerFingerprint(CourierSignals.WOLT_PACKAGE, sameOfferLater),
        )
        assertNotEquals(
            firstId,
            CourierSignals.offerFingerprint(CourierSignals.WOLT_PACKAGE, differentRoute),
        )
    }
}
