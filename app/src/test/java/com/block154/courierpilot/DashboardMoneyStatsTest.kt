package com.block154.courierpilot

import android.content.ContentValues
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DashboardMoneyStatsTest {
    private lateinit var context: Context
    private lateinit var database: OfferDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = OfferDatabase.get(context)
        database.writableDatabase.delete("offers", null, null)
        database.writableDatabase.delete("market_observations", null, null)
        context.getSharedPreferences("courier_offer_repairs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun offerInsertPersistsNativeCurrencyMetadata() {
        val id = database.insert(
            record(
                capturedAt = 1_000_000L,
                packageName = CourierSignals.BOLT_PACKAGE,
                platform = "Bolt",
                priceMinor = 1_234,
                currencyCode = "PLN",
                fractionDigits = 2,
                distanceMeters = 2_000,
                captureKey = "pln-persist",
            )
        )

        val restored = database.findById(id)!!
        assertEquals("PLN", restored.currencyCode)
        assertEquals(2, restored.currencyFractionDigits)
        assertEquals(1_234, restored.priceCents)
    }

    @Test
    fun homogeneousCurrencySummaryScalesMinorUnitsCorrectly() {
        val now = System.currentTimeMillis()
        database.insert(
            record(now - 120_000L, CourierSignals.WOLT_PACKAGE, "Wolt", 1_234, "GBP", 2, 2_000, "gbp-1")
        )
        database.insert(
            record(now, CourierSignals.WOLT_PACKAGE, "Wolt", 2_234, "GBP", 2, 2_000, "gbp-2")
        )

        val summary = DashboardMoneyStats.summarySince(database, now - 180_000L)
        assertEquals(2, summary.count)
        assertEquals("GBP", summary.currencyCode)
        assertEquals(2, summary.fractionDigits)
        assertEquals(17.34, summary.averageMoney!!, 0.0001)
        assertEquals(8.67, summary.averageMoneyPerKm!!, 0.0001)
        assertEquals("GBP 17.34", formatDashboardMoney(summary.averageMoney, summary.currencyCode, summary.fractionDigits))
    }

    @Test
    fun mixedCurrenciesNeverProduceFakeMonetaryAverage() {
        val now = System.currentTimeMillis()
        database.insert(
            record(now - 120_000L, CourierSignals.WOLT_PACKAGE, "Wolt", 500, "EUR", 2, 2_000, "eur")
        )
        database.insert(
            record(now, CourierSignals.BOLT_PACKAGE, "Bolt", 500, "GBP", 2, 2_000, "gbp")
        )

        val summary = DashboardMoneyStats.summarySince(database, now - 180_000L)
        assertEquals(2, summary.count)
        assertTrue(summary.mixedCurrency)
        assertNull(summary.currencyCode)
        assertNull(summary.averageMoney)
        assertNull(summary.averageMoneyPerKm)
        assertEquals("Mixed currencies", formatDashboardMoney(summary.averageMoney, summary.currencyCode, summary.fractionDigits, summary.mixedCurrency))
        assertEquals("Mixed currencies", formatDashboardRate(summary.averageMoneyPerKm, summary.currencyCode, summary.mixedCurrency))

        val day = DashboardMoneyStats.dailyStats(database, 1).single()
        assertTrue(day.mixedCurrency)
        assertNull(day.averageMoney)
        assertNull(day.averageMoneyPerKm)
    }

    @Test
    fun historicalDefaultEurMetadataCanBeRepairedOnlyFromExactRawMoneyEvidence() {
        val oldBugRow = record(
            capturedAt = 1_000_000L,
            packageName = CourierSignals.BOLT_PACKAGE,
            platform = "Bolt",
            priceMinor = 1_234,
            currencyCode = "EUR",
            fractionDigits = 2,
            distanceMeters = 2_000,
            captureKey = "old-default",
        ).copy(rawText = "£12.34\nAccept")
        val actual = MoneyAmount(1_234L, "GBP", 2)

        assertEquals(actual, OfferDataRepair.trustedHistoricalCurrencyMetadata(oldBugRow, actual))
        assertNull(
            OfferDataRepair.trustedHistoricalCurrencyMetadata(
                oldBugRow,
                MoneyAmount(1_235L, "GBP", 2),
            )
        )
        assertNull(
            OfferDataRepair.trustedHistoricalCurrencyMetadata(
                oldBugRow.copy(rawText = "12.34\nAccept"),
                actual,
            )
        )
        assertNull(
            OfferDataRepair.trustedHistoricalCurrencyMetadata(
                oldBugRow.copy(currencyCode = "PLN"),
                actual,
            )
        )
    }

    @Test
    fun revision19RepairsOldInsertDefaultWithoutChangingCapturedAmount() {
        val raw = "16 min, 19,50 PLN\nAccept"
        val oldId = database.writableDatabase.insertOrThrow(
            "offers",
            null,
            ContentValues().apply {
                put("captured_at", System.currentTimeMillis())
                put("platform", "Bolt")
                put("package_name", CourierSignals.BOLT_PACKAGE)
                put("price_cents", 1_950)
                put("currency_code", "EUR")
                put("currency_fraction_digits", 2)
                put("distance_meters", 2_000)
                put("restaurant", "Historical test")
                put("screenshot_uri", "")
                put("screenshot_filename", "")
                put("raw_text", raw)
                put("capture_key", "old-currency-default")
            },
        )
        context.getSharedPreferences("courier_offer_repairs", Context.MODE_PRIVATE)
            .edit()
            .putInt("parser_repair_revision", 18)
            .commit()

        OfferDataRepair.runIfNeeded(context)

        val repaired = database.findById(oldId)!!
        assertEquals(1_950, repaired.priceCents)
        assertEquals("PLN", repaired.currencyCode)
        assertEquals(2, repaired.currencyFractionDigits)
        assertEquals(
            19,
            context.getSharedPreferences("courier_offer_repairs", Context.MODE_PRIVATE)
                .getInt("parser_repair_revision", 0),
        )
    }

    private fun record(
        capturedAt: Long,
        packageName: String,
        platform: String,
        priceMinor: Int,
        currencyCode: String,
        fractionDigits: Int,
        distanceMeters: Int,
        captureKey: String,
    ) = OfferRecord(
        capturedAt = capturedAt,
        platform = platform,
        packageName = packageName,
        priceCents = priceMinor,
        currencyCode = currencyCode,
        currencyFractionDigits = fractionDigits,
        distanceMeters = distanceMeters,
        restaurant = captureKey,
        screenshotUri = "",
        screenshotFilename = "",
        rawText = "$platform $captureKey $priceMinor $currencyCode",
        captureKey = captureKey,
    )
}
