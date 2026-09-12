package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class WoltSingleRouteAccessibilityRecoveryTest {
    private val sparse = ParsedOffer(
        priceCents = 482,
        money = MoneyAmount(482, "EUR", 2),
        distanceMeters = 6_900,
        restaurant = null,
    )

    private val richTree = """
        €4.82
        2 stops (6.9 km) • 14–21 min
        Hong Kong (Basanavičiaus g.)
        Basanavičiaus g 19, Vilnius, LT-03108
        Customer drop-off
        Pulko 3, Vilnius, 08221
        Accept
    """.trimIndent()

    @Test
    fun enrichesSparseVisibleOfferOnlyFromExactNumericMatch() {
        val recovered = WoltSingleRouteAccessibilityRecovery.recover(sparse, richTree)
        assertNotNull(recovered)
        assertEquals(listOf("Basanavičiaus g 19, Vilnius, LT-03108"), recovered!!.pickupAddresses)
        assertEquals(listOf("Pulko 3, Vilnius, 08221"), recovered.dropoffAddresses)
        assertEquals(482, recovered.priceCents)
        assertEquals(6_900, recovered.distanceMeters)
    }

    @Test
    fun rejectsStaleTreeWithDifferentPriceOrDistance() {
        assertNull(WoltSingleRouteAccessibilityRecovery.recover(sparse, richTree.replace("€4.82", "€7.83")))
        assertNull(WoltSingleRouteAccessibilityRecovery.recover(sparse, richTree.replace("6.9 km", "4.4 km")))
    }

    @Test
    fun rejectsBatchTreeFromSingleOfferRecovery() {
        val batch = """
            €4.82
            3 stops (6.9 km) • 14–21 min
            Hong Kong (Basanavičiaus g.)
            Basanavičiaus g 19, Vilnius, LT-03108
            Customer drop-off
            Pulko 3, Vilnius, 08221
            Customer drop-off
            Žirmūnų g. 55, Vilnius, 09110
            Accept
        """.trimIndent()
        assertNull(WoltSingleRouteAccessibilityRecovery.recover(sparse, batch))
    }
}
