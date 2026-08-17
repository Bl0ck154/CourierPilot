package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferParserTest {

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
}
