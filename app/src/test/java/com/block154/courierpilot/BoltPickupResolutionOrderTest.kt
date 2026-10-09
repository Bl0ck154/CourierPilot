package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Test

class BoltPickupResolutionOrderTest {
    @Test fun preservesInputOrderWhenCallbacksCompleteOutOfOrder() {
        val completed = mutableListOf<String?>(null, null, null)
        completed[2] = "third"
        completed[0] = "first"
        completed[1] = "second"
        assertEquals(
            listOf("first", "second", "third"),
            BoltPickupResolutionOrder.successfulInRequestOrder(completed),
        )
    }

    @Test fun omitsUnresolvedPickupWithoutShufflingResolvedResults() {
        assertEquals(
            listOf("first", "third"),
            BoltPickupResolutionOrder.successfulInRequestOrder(
                listOf("first", null, "third"),
            ),
        )
    }
}
