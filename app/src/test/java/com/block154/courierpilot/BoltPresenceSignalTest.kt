package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Test

class BoltPresenceSignalTest {
    @Test
    fun currentBoltBackgroundNotificationCountsAsOnline() {
        val text = "Bolt Courier app is running We keep you active while app is in background"
        assertEquals(PresenceSignal.ONLINE, CourierSignals.detectPresence(text))
    }
}
