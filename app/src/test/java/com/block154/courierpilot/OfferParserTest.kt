package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferParserTest {

    @Test
    fun parsesRedesignedWoltSingleOffer() {
        val parsed = OfferParser.parse(
            """
            €6.56
            2 stops (5.4 km) • 15–22 min
            Collect cash
            Jammi (Tauro kalnas)
            Tauro g. 3, Vilnius, LT-03106
            Customer drop-off
            Vilkpėdės gatvė 2A, Vilnius, 03151
            Estimated earnings for the full delivery
            Accept
            """.trimIndent()
        )

        assertEquals(656, parsed.priceCents)
        assertEquals(5400, parsed.distanceMeters)
        assertEquals(listOf("Jammi (Tauro kalnas)"), parsed.merchantNames)
        assertEquals(listOf("Tauro g. 3, Vilnius, LT-03106"), parsed.pickupAddresses)
        assertEquals(listOf("Vilkpėdės gatvė 2A, Vilnius, 03151"), parsed.dropoffAddresses)
        assertEquals(1, parsed.deliveryCount)
        assertEquals(15, parsed.estimatedMinutesMin)
        assertEquals(22, parsed.estimatedMinutesMax)
    }

    @Test
    fun prefersCompleteOcrCopyWhenAccessibilityCopyOmitsModernWoltAddresses() {
        val parsed = OfferParser.parse(
            """
            €6.56
            2 stops (5.4 km) • 15–22 min
            Jammi (Tauro kalnas)
            Customer drop-off
            Estimated earnings for the full delivery
            Accept
            €6.56
            2 stops (5.4 km) • 15–22 min
            Jammi (Tauro kalnas)
            Tauro g. 3, Vilnius, LT-03106
            Customer drop-off
            Vilkpėdės gatvė 2A, Vilnius, 03151
            Estimated earnings for the full delivery
            Accept
            """.trimIndent()
        )

        assertEquals(listOf("Tauro g. 3, Vilnius, LT-03106"), parsed.pickupAddresses)
        assertEquals(listOf("Vilkpėdės gatvė 2A, Vilnius, 03151"), parsed.dropoffAddresses)
        assertEquals(1, parsed.deliveryCount)
        assertTrue(AutomaticWoltRouteCoordinator.routeFingerprint(parsed) != null)
    }

    @Test
    fun recoversSinglePickupWhenOcrEmitsSummaryAfterPickupAddress() {
        val parsed = OfferParser.parse(
            """
            €1.90
            2 stops (0.8 km) • 3–9 min
            Hesburger (Vokiečių)
            Customer drop-off
            Arklių gatvė 36, Vilnius, 01305
            Accept
            Hesburger (Vokiečių)
            Vokiečių g. 12, Vilnius, LT01130
            €1.90
            2 stops (0.8 km) • 3–9 min
            Customer drop-off
            Arklių gatvė 36, Vilnius, 01305
            Accept
            """.trimIndent()
        )

        assertEquals(190, parsed.priceCents)
        assertEquals(800, parsed.distanceMeters)
        assertEquals(listOf("Vokiečių g. 12, Vilnius, LT01130"), parsed.pickupAddresses)
        assertEquals(listOf("Arklių gatvė 36, Vilnius, 01305"), parsed.dropoffAddresses)
        assertEquals(1, parsed.deliveryCount)
        assertTrue(AutomaticWoltRouteCoordinator.routeFingerprint(parsed) != null)
    }

    @Test
    fun infersSingleDropoffWhenNewWoltOcrMissesCustomerLabel() {
        val parsed = OfferParser.parse(
            """
            €3.32
            2 stops (3.1 km) • 7–14 min
            Holy Donut (Vokiečių g.)
            Vokiečių g. 9, Vilnius, 01130
            Brolių gatvė 21, Vilnius
            Accept
            """.trimIndent()
        )

        assertEquals(332, parsed.priceCents)
        assertEquals(3100, parsed.distanceMeters)
        assertEquals(listOf("Vokiečių g. 9, Vilnius, 01130"), parsed.pickupAddresses)
        assertEquals(listOf("Brolių gatvė 21, Vilnius"), parsed.dropoffAddresses)
        assertEquals(1, parsed.deliveryCount)
        assertTrue(AutomaticWoltRouteCoordinator.routeFingerprint(parsed) != null)
    }

    @Test
    fun parsesRedesignedWoltCollapsedStackedOfferWithoutInventingDropoffs() {
        val parsed = OfferParser.parse(
            """
            €13.04
            4 stops (14.0 km) • 29–42 min
            Eat More Chinese & Shimai Sushi (Palangos g.)
            Palangos g. 2, Vilnius, LT01117
            Talutti Bakes'n'Shakes City
            Vilniaus g. 35, Vilnius, LT01119
            Multiple drop-offs (2 stops)
            Estimated earnings for the full delivery
            Accept
            """.trimIndent()
        )

        assertEquals(1304, parsed.priceCents)
        assertEquals(14000, parsed.distanceMeters)
        assertEquals(
            listOf("Eat More Chinese & Shimai Sushi (Palangos g.)", "Talutti Bakes'n'Shakes City"),
            parsed.merchantNames,
        )
        assertEquals(
            listOf("Palangos g. 2, Vilnius, LT01117", "Vilniaus g. 35, Vilnius, LT01119"),
            parsed.pickupAddresses,
        )
        assertTrue(parsed.dropoffAddresses.isEmpty())
        assertEquals(2, parsed.deliveryCount)
        assertEquals(29, parsed.estimatedMinutesMin)
        assertEquals(42, parsed.estimatedMinutesMax)
    }

    @Test
    fun combinesRedesignedWoltCardAndExpandedDropoffSheetIntoRoutableOffer() {
        val parsed = OfferParser.parse(
            """
            €12.46
            4 stops (14.9 km) • 38–51 min
            Sushi Out (Upės g.)
            Upės g. 6, Vilnius, LT-09309
            Guacamole Mexican Grill (Baltas tiltas)
            Upės g. 6, Vilnius, LT-09309
            Multiple drop-offs (2 stops)
            Estimated earnings for the full delivery
            Accept
            Multiple drop-offs
            2 stops
            V. Grybo Gatvė 34
            Vilnius
            Kaukyšos gatvė 18
            Vilnius, 11342
            Done
            """.trimIndent()
        )

        assertEquals(1246, parsed.priceCents)
        assertEquals(14900, parsed.distanceMeters)
        assertEquals(listOf("Sushi Out (Upės g.)", "Guacamole Mexican Grill (Baltas tiltas)"), parsed.merchantNames)
        assertEquals(listOf("Upės g. 6, Vilnius, LT-09309"), parsed.pickupAddresses)
        assertEquals(listOf("V. Grybo Gatvė 34", "Kaukyšos gatvė 18"), parsed.dropoffAddresses)
        assertEquals(2, parsed.deliveryCount)
        assertTrue(AutomaticWoltRouteCoordinator.routeFingerprint(parsed) != null)
    }

    @Test
    fun parsesRealWoltSingleOffer() {
        val parsed = OfferParser.parse(
            """
            €4.48
            Expected earnings for the full delivery
            Delivery from
            Sushi Lounge (Dominikonų g.)
            Route distance
            8.4 km
            Estimated
            19 - 32 min
            Timeline
            Sushi Lounge (Dominikonų g.)
            1 min
            Dominikonų g. 6, LT-01131 Vilnius
            Rasa T.
            19 min
            Žirmūnų gatvė 54 81, Vilnius
            Accept
            """.trimIndent()
        )

        assertEquals(448, parsed.priceCents)
        assertEquals(8400, parsed.distanceMeters)
        assertEquals(listOf("Sushi Lounge (Dominikonų g.)"), parsed.merchantNames)
        assertEquals(listOf("Dominikonų g. 6, LT-01131 Vilnius"), parsed.pickupAddresses)
        assertEquals(listOf("Rasa T."), parsed.customerNames)
        assertEquals(listOf("Žirmūnų gatvė 54 81, Vilnius"), parsed.dropoffAddresses)
        assertEquals(1, parsed.deliveryCount)
        assertEquals(19, parsed.estimatedMinutesMin)
        assertEquals(32, parsed.estimatedMinutesMax)
    }

    @Test
    fun parsesWoltSingleWhenAccessibilityOmitsRepeatedTimelineMerchant() {
        val parsed = OfferParser.parse(
            """
            €4.48
            Expected earnings for the full delivery
            Delivery from
            Sushi Lounge (Dominikonų g.)
            Route distance
            8.4 km
            Estimated
            19 - 32 min
            Timeline
            1 min
            Dominikonų g. 6, LT-01131 Vilnius
            Rasa T.
            19 min
            Žirmūnų gatvė 54 81, Vilnius
            """.trimIndent()
        )

        assertEquals(listOf("Sushi Lounge (Dominikonų g.)"), parsed.merchantNames)
        assertEquals(listOf("Dominikonų g. 6, LT-01131 Vilnius"), parsed.pickupAddresses)
        assertEquals(listOf("Rasa T."), parsed.customerNames)
        assertEquals(listOf("Žirmūnų gatvė 54 81, Vilnius"), parsed.dropoffAddresses)
    }

    @Test
    fun doesNotMisclassifyDropoffAsPickupWhenPickupAddressIsMissing() {
        val parsed = OfferParser.parse(
            """
            €4.48
            Expected earnings for the full delivery
            Delivery from
            Sushi Lounge (Dominikonų g.)
            Route distance
            8.4 km
            Estimated
            19 - 32 min
            Timeline
            Rasa T.
            19 min
            Žirmūnų gatvė 54 81, Vilnius
            Accept
            """.trimIndent()
        )

        assertEquals(listOf("Sushi Lounge (Dominikonų g.)"), parsed.merchantNames)
        assertTrue(parsed.pickupAddresses.isEmpty())
        assertEquals(listOf("Rasa T."), parsed.customerNames)
        assertEquals(listOf("Žirmūnų gatvė 54 81, Vilnius"), parsed.dropoffAddresses)
    }

    @Test
    fun doesNotTreatOfferAsReadyBeforePriceAppears() {
        val parsed = OfferParser.parse(
            """
            Delivery from
            Manami (Vilniaus g.)
            Route distance
            8.8 km
            Estimated
            14 - 27 min
            """.trimIndent()
        )
        assertNull(parsed.priceCents)
        assertEquals(8800, parsed.distanceMeters)
    }

    @Test
    fun rejectsZeroPlaceholderPrice() {
        val parsed = OfferParser.parse("€0.00\nExpected earnings for the full delivery")
        assertNull(parsed.priceCents)
    }

    @Test
    fun parsesRealWoltStackedOffer() {
        val parsed = OfferParser.parse(
            """
            €6.16
            Expected earnings for the full delivery
            2 deliveries from
            Rustam Mangal by Ugruzina, Sushi Masters
            Route distance
            9 km
            Estimated
            31 - 44 min
            Timeline
            Rustam Mangal by Ugruzina
            4 min
            Trakų g. 16, LT01132 Vilnius
            Sushi Masters
            10 min
            Totorių g. 24, LT-01121 Vilnius
            Arnas P.
            24 min
            S. Žukausko gatvė 1 Žukausko g. 1-111, Vilnius
            Софья C.
            37 min
            Žirmūnų gatvė 106C, 09121 Vilnius
            Accept
            """.trimIndent()
        )

        assertEquals(616, parsed.priceCents)
        assertEquals(9000, parsed.distanceMeters)
        assertEquals(listOf("Rustam Mangal by Ugruzina", "Sushi Masters"), parsed.merchantNames)
        assertEquals(
            listOf("Trakų g. 16, LT01132 Vilnius", "Totorių g. 24, LT-01121 Vilnius"),
            parsed.pickupAddresses,
        )
        assertEquals(listOf("Arnas P.", "Софья C."), parsed.customerNames)
        assertEquals(2, parsed.deliveryCount)
        assertEquals(31, parsed.estimatedMinutesMin)
        assertEquals(44, parsed.estimatedMinutesMax)
    }

    @Test
    fun parsesSecondRealWoltStackedOffer() {
        val parsed = OfferParser.parse(
            """
            €5.35
            Expected earnings for the full delivery
            2 deliveries from
            Daily Poison, Druska Miltai Vanduo (Jasinskio g.)
            Route distance
            5.5 km
            Estimated
            16 - 29 min
            Timeline
            Daily Poison
            Ready
            J. Jasinskio g. 14A - 101, LT01112 Vilnius
            Druska Miltai Vanduo (Jasinskio g.)
            7 min
            Jasinskio g. 2, LT-01112 Vilnius
            Vadim K.
            6 min
            T. Ševčenkos gatvė 16A, 03111 Vilnius
            jolanta u.
            13 min
            Vytenio g. 50, 03202 Vilnius
            Accept
            """.trimIndent()
        )

        assertEquals(535, parsed.priceCents)
        assertEquals(5500, parsed.distanceMeters)
        assertEquals(listOf("Daily Poison", "Druska Miltai Vanduo (Jasinskio g.)"), parsed.merchantNames)
        assertEquals(listOf("Vadim K.", "jolanta u."), parsed.customerNames)
        assertEquals(2, parsed.deliveryCount)
        assertEquals(16, parsed.estimatedMinutesMin)
        assertEquals(29, parsed.estimatedMinutesMax)
    }

    @Test
    fun detectsTwoDeliveriesFromOneWoltVenueEvenWithSingularHeader() {
        val parsed = OfferParser.parse(
            """
            €7.42
            Expected earnings for the full delivery
            Delivery from
            Sushi Lounge (Dominikonų g.)
            Route distance
            6.1 km
            Estimated
            22 - 35 min
            Timeline
            Sushi Lounge (Dominikonų g.)
            Ready
            Dominikonų g. 6, LT-01131 Vilnius
            Rasa T.
            14 min
            Žirmūnų gatvė 54 81, Vilnius
            Mantas K.
            28 min
            Kalvarijų g. 125, LT-08221 Vilnius
            Accept
            """.trimIndent()
        )

        assertEquals(742, parsed.priceCents)
        assertEquals(listOf("Sushi Lounge (Dominikonų g.)"), parsed.merchantNames)
        assertEquals(1, parsed.pickupAddresses.size)
        assertEquals(listOf("Rasa T.", "Mantas K."), parsed.customerNames)
        assertEquals(2, parsed.dropoffAddresses.size)
        assertEquals(2, parsed.deliveryCount)
    }

    @Test
    fun prefersWoltFullDeliveryEarningsOverOtherCurrencyAmounts() {
        val parsed = OfferParser.parse(
            """
            €2.10
            Some other visible amount
            €7.42
            Expected earnings for the full delivery
            Delivery from
            Sushi Lounge (Dominikonų g.)
            Route distance
            6.1 km
            Timeline
            Rasa T.
            Žirmūnų gatvė 54 81, Vilnius
            Mantas K.
            Kalvarijų g. 125, LT-08221 Vilnius
            """.trimIndent()
        )

        assertEquals(742, parsed.priceCents)
        assertEquals(2, parsed.deliveryCount)
    }

    @Test
    fun parsesRealBoltOfferWithoutInventingDistance() {
        val parsed = OfferParser.parse(
            """
            Decline
            Show map
            TIO BIGOTES Ispaniškos Empanados (Rūdninkų str.)
            Rūdninkų 8-105, Vilnius
            ~9 min
            ~7 min
            16 min, 4,45 €
            """.trimIndent()
        )

        assertEquals(445, parsed.priceCents)
        assertNull(parsed.distanceMeters)
        assertEquals(listOf("TIO BIGOTES Ispaniškos Empanados (Rūdninkų str.)"), parsed.merchantNames)
        assertEquals(listOf("Rūdninkų 8-105, Vilnius"), parsed.pickupAddresses)
        assertEquals(16, parsed.estimatedMinutesMin)
        assertEquals(16, parsed.estimatedMinutesMax)
        assertTrue(parsed.customerNames.isEmpty())
        assertTrue(parsed.dropoffAddresses.isEmpty())
    }

    @Test
    fun prefersBoltMerchantCardTitleOverInterleavedMapLabels() {
        val parsed = OfferParser.parse(
            """
            Decline
            OLD TOWN
            No Forks Mexican Grill (Vokiečių str.)
            Railway Park
            Vokiečių g. 9, Vilnius
            ~11 min
            Vilnius
            ~8 min
            19 min, 3,28 €
            """.trimIndent()
        )

        assertEquals(328, parsed.priceCents)
        assertEquals("No Forks Mexican Grill (Vokiečių str.)", parsed.restaurant)
        assertEquals(listOf("No Forks Mexican Grill (Vokiečių str.)"), parsed.merchantNames)
        assertEquals(listOf("Vokiečių g. 9, Vilnius"), parsed.pickupAddresses)
    }

    @Test
    fun prefersSimpleBoltMerchantOverNearbyPoiLabel() {
        val parsed = OfferParser.parse(
            """
            Decline
            Show map
            KFC
            Railway Park
            Gedimino pr. 5, Vilnius
            ~9 min
            ~7 min
            16 min, 4,45 €
            """.trimIndent()
        )

        assertEquals(listOf("KFC"), parsed.merchantNames)
        assertEquals(listOf("Gedimino pr. 5, Vilnius"), parsed.pickupAddresses)
    }
    @Test
    fun ignoresUnanchoredMoneyWhileWoltEarningsAreStillLoading() {
        val parsed = OfferParser.parse(
            """
            Expected earnings for the full delivery
            Delivery from
            Fresh Mesh
            Route distance
            1.0 km
            Estimated
            5 - 11 min
            Accept
            Account
            €28.00
            """.trimIndent()
        )

        assertNull(parsed.money)
        assertNull(parsed.priceCents)
        assertEquals(1000, parsed.distanceMeters)
    }

    @Test
    fun findsPriceAtLaterOcrCopyOfWoltEarningsAnchor() {
        val parsed = OfferParser.parse(
            """
            Expected earnings for the full delivery
            Delivery from
            Fresh Mesh
            Route distance
            1.0 km
            Accept
            €1.76
            Expected earnings for the full delivery
            Delivery from
            Fresh Mesh
            """.trimIndent()
        )

        assertEquals(MoneyAmount(176, "EUR", 2), parsed.money)
        assertEquals(176, parsed.priceCents)
    }

}
