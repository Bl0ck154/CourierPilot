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
class OfferHistoryTruthV01551Test {
    @Test
    fun rejectsPersistedRouteThatIsWildlyLongerThanPlatformDistance() {
        assertNull(OfferRouteDistancePolicy.trustedCalculatedRouteMeters(2_900, 8_618))
        assertEquals(2_900, OfferRouteDistancePolicy.effectiveMeters(2_900, 8_618))
        assertTrue(OfferRouteDistancePolicy.calculatedRouteWasRejected(2_900, 8_618))
    }

    @Test
    fun keepsReasonableCalculatedRoute() {
        assertEquals(3_725, OfferRouteDistancePolicy.trustedCalculatedRouteMeters(3_500, 3_725))
        assertEquals(3_725, OfferRouteDistancePolicy.effectiveMeters(3_500, 3_725))
    }

    @Test
    fun recentCardTitleDropsLeakedBuildingSuffixAndOcrGarbage() {
        val record = OfferRecord(
            capturedAt = 1L,
            platform = "Wolt",
            packageName = CourierSignals.WOLT_PACKAGE,
            priceCents = 332,
            distanceMeters = 2_900,
            restaurant = "B Holy Donut (Vokiečiųg.), 8 min",
            screenshotUri = "",
            screenshotFilename = "",
            rawText = "",
            merchantNames = listOf("B Holy Donut (Vokiečiųg.)", "8 min"),
            pickupAddresses = listOf("Vokiečių g. 9, Vilnius, 01130"),
            dropoffAddresses = listOf("Smolensko gatvė 10 B, Vilnius, 03201"),
        )

        assertEquals("Holy Donut (Vokiečių g.)", OfferPresentation.merchantTitle(record))
    }

    @Test
    fun reparsingDoesNotReplaceCleanerStoredAddressWithTruncatedOcrAddress() {
        val record = OfferRecord(
            capturedAt = 1L,
            platform = "Wolt",
            packageName = CourierSignals.WOLT_PACKAGE,
            priceCents = 332,
            distanceMeters = 2_900,
            restaurant = "Holy Donut (Vokiečių g.)",
            screenshotUri = "",
            screenshotFilename = "",
            rawText = """
                €3.32
                2 stops (2.9 km) · 7–14 min
                B Holy Donut (Vokiečiųg.)
                Vokiečių g. 9, Vilnius, 01130
                olensko gatvė 10 B, Vilnius, 03201
                Accept
            """.trimIndent(),
            merchantNames = listOf("Holy Donut (Vokiečių g.)"),
            pickupAddresses = listOf("Vokiečių g. 9, Vilnius, 01130"),
            dropoffAddresses = listOf("Smolensko gatvė 10 B, Vilnius, 03201"),
            deliveryCount = 1,
        )

        val repaired = record.withCurrentParsedStructure()

        assertEquals(listOf("Holy Donut (Vokiečių g.)"), repaired.merchantNames)
        assertEquals(listOf("Smolensko gatvė 10 B, Vilnius, 03201"), repaired.dropoffAddresses)
        assertEquals(2_900, repaired.distanceMeters)
    }

    @Test
    fun databaseSummaryFallsBackToPlatformDistanceForBadHistoricalRoute() {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = OfferDatabase.get(context)
        val now = System.currentTimeMillis()
        val unique = (System.nanoTime() and 0xfffffff).coerceAtLeast(10_000L)
        val captureKey = "history-truth-$unique"
        val platform = "HistoryTruth$unique"
        val offerId = database.insert(
            OfferRecord(
                capturedAt = now,
                platform = platform,
                packageName = "test.history.$unique",
                priceCents = 332,
                distanceMeters = 2_900,
                restaurant = "Holy Donut (Vokiečių g.)",
                screenshotUri = "",
                screenshotFilename = "",
                rawText = "€3.32\n2 stops (2.9 km)\nHoly Donut (Vokiečių g.)\nVokiečių g. 9, Vilnius\nSmolensko gatvė 10 B, Vilnius\nAccept",
                captureKey = captureKey,
            )
        )
        database.updateMarketRoute(
            offerId = offerId,
            routeDistanceMeters = 8_618,
            routeSource = "FULL_valhalla_mean",
            city = MarketCity("test-$unique", "Vilnius", "LT", now),
        )

        val summary = database.summarySince(now - 2_000L, platform)

        assertEquals(1, summary.count)
        assertEquals(2_900.0, summary.averageDistanceMeters!!, 0.001)
        assertEquals(3320.0 / 2_900.0, summary.averageEurPerKm!!, 0.0001)
        val stored = database.findById(offerId)!!
        assertEquals(2_900, stored.effectiveRouteDistanceMeters)
    }
    @Test
    fun repairRevisionClearsOldImplausibleCalculatedRoute() {
        val context: Context = RuntimeEnvironment.getApplication()
        val database = OfferDatabase.get(context)
        val now = System.currentTimeMillis()
        val unique = (System.nanoTime() and 0xfffffff).coerceAtLeast(20_000L)
        val offerId = database.insert(
            OfferRecord(
                capturedAt = now,
                platform = "Wolt",
                packageName = CourierSignals.WOLT_PACKAGE,
                priceCents = 332,
                distanceMeters = 2_900,
                restaurant = "Holy Donut (Vokiečių g.)",
                screenshotUri = "",
                screenshotFilename = "",
                rawText = "€3.32\n2 stops (2.9 km)\nHoly Donut (Vokiečių g.)\nVokiečių g. 9, Vilnius\nSmolensko gatvė 10 B, Vilnius\nAccept",
                captureKey = "repair-history-$unique",
            )
        )
        database.updateMarketRoute(
            offerId = offerId,
            routeDistanceMeters = 8_618,
            routeSource = "FULL_valhalla_mean",
            city = MarketCity("repair-$unique", "Vilnius", "LT", now),
        )
        context.getSharedPreferences("courier_offer_repairs", Context.MODE_PRIVATE)
            .edit()
            .putInt("parser_repair_revision", 15)
            .commit()

        OfferDataRepair.runIfNeeded(context)

        val repaired = database.findById(offerId)!!
        assertNull(repaired.marketRouteDistanceMeters)
        assertEquals(2_900, repaired.effectiveRouteDistanceMeters)
    }

}
