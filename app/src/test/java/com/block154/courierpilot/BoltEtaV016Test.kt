package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Test

class BoltEtaV016Test {
    @Test fun threeRealBoltLayoutFixtures() {
        val cases = listOf(
            Triple(
                "Casa Della Pasta (Vokiečių str.)\nVokiečių gatvė 13 01130 Vilnius Lithuania\n~6 min\n~10 min\n15 min, 2,41 €",
                BoltEtas(6, 10, 15), "Casa Della Pasta"
            ),
            Triple(
                "Sushi Square (Vilniaus str.)\nVilniaus g. 47, Vilnius, 01119\n~7 min\n~14 min\n20 min, 3,98 €",
                BoltEtas(7, 14, 20), "Sushi Square"
            ),
            Triple(
                "iLunch (A. Goštauto str.)\nA. Goštauto g. 40A, Vilnius\n~7 min\n~10 min\n17 min, 3,74 €",
                BoltEtas(7, 10, 17), "iLunch"
            ),
        )
        cases.forEach { (text, expected, _) ->
            assertEquals(expected, BoltEtaExtractor.extract(text))
        }
        assertEquals(BoltEtas(null, null, null), BoltEtaExtractor.extract("No ETA data"))
    }

    @Test fun namedCustomerAndPickupRowsAreDistinguished() {
        val raw = "Customer drop-off ~12 min\nPickup restaurant ~5 min\n17 min, 3,74 €"
        assertEquals(BoltEtas(5, 12, 17), BoltEtaExtractor.extract(raw))
    }

    @Test fun ewmaLearnsAndOutlierDoesNotPoisonSpeed() {
        val model = BoltEtaDistanceModel()
        assertEquals(2300, model.estimateMeters(10))
        model.observe(10, 3000)
        assertEquals(2405, model.estimateMeters(10))
        model.observe(10, 20_000)
        model.observe(0, 100)
        assertEquals(2405, model.estimateMeters(10))
    }
}
