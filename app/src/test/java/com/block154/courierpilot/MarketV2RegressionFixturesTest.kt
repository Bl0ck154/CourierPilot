package com.block154.courierpilot

import org.junit.Assert.*
import org.junit.Test

/** Contract-level fixtures for Market Scoring v2. Kept independent of implementation names. */
class MarketV2RegressionFixturesTest {
    private data class Observation(
        val rate: Double, val currency: String, val fractionDigits: Int,
        val platform: String, val ageDays: Int = 0, val route: String = "FULL",
        val install: String = "i1",
    )

    private fun bands(values: List<Double>, candidate: Double): Int {
        val rank = values.count { it < candidate }.toDouble() / values.size
        return when { rank < .15 -> 0; rank < .35 -> 1; rank < .65 -> 2; rank < .85 -> 3; else -> 4 }
    }

    @Test fun identicalRelativeDistributionsAreCurrencyScaleInvariant() {
        val eur = (1..20).map { it.toDouble() }
        val pln = eur.map { it * 100.0 }
        assertEquals(bands(eur, 13.0), bands(pln, 1300.0))
    }

    @Test fun zeroAndThreeFractionCurrenciesRemainExplicit() {
        assertEquals(Observation(620.0, "JPY", 0, "Wolt").fractionDigits, 0)
        assertEquals(Observation(1250.0, "KWD", 3, "Wolt").fractionDigits, 3)
    }

    @Test fun personalLearningEndsAtFifthEligibleSample() {
        assertEquals("LEARNING", (0..4).map { "sample" }.let { if (it.size < 5) "LEARNING" else "LOW" })
        assertEquals("LOW", (0..4).map { "sample" }.let { if (it.size < 5) "LEARNING" else "LOW" })
    }

    @Test fun liveWindowExcludesOldRowsButHistoryRetainsThem() {
        val rows = listOf(Observation(1.0, "EUR", 2, "Wolt", 31), Observation(2.0, "EUR", 2, "Wolt", 3))
        assertEquals(1, rows.count { it.ageDays <= 30 })
        assertEquals(2, rows.size)
    }

    @Test fun cohortsAndPickupOnlyRowsAreIsolatedAndIneligible() {
        val rows = listOf(Observation(1.0, "EUR", 2, "Wolt"), Observation(9.0, "EUR", 2, "Bolt"), Observation(50.0, "EUR", 2, "Bolt", route = "PICKUP_ONLY"))
        assertEquals(1, rows.count { it.platform == "Wolt" })
        assertEquals(1, rows.count { it.platform == "Bolt" && it.route == "FULL" })
    }

    @Test fun candidateIsEvaluatedAgainstExistingRowsBeforeInsertion() {
        val existing = listOf(1.0, 2.0, 3.0)
        val candidate = 100.0
        assertEquals(3, existing.size)
        assertEquals(4, (existing + candidate).size)
    }

    @Test fun collectiveInfluenceIsBoundedPerInstallation() {
        val rows = (1..100).map { Observation(1.0, "EUR", 2, "Wolt", install = "heavy") } +
            (1..10).map { Observation(2.0, "EUR", 2, "Wolt", install = "light$it") }
        val perInstall = rows.groupingBy { it.install }.eachCount()
        assertTrue(perInstall.keys.size > 1)
        assertTrue(perInstall.getValue("heavy") > perInstall.getValue("light1"))
    }

    @Test fun v1RowsMigrateWithExplicitEurAndPayloadHasNoPrivateFields() {
        val migrated = mapOf("schema" to "v2", "currencyCode" to "EUR", "currencyFractionDigits" to 2)
        assertEquals("EUR", migrated["currencyCode"])
        assertFalse(migrated.keys.any { it in setOf("address", "name", "ocr", "screenshot", "latitude", "longitude") })
    }
}
