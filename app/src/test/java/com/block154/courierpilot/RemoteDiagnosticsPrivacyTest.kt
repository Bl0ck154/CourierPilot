package com.block154.courierpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDiagnosticsPrivacyTest {

    @Test
    fun notificationVisibleTextIsRedactedButTechnicalPrefixSurvives() {
        val sanitized = RemoteDiagnosticsPrivacy.sanitize(
            "notification_ignored",
            "score=4; shape[channel=orders,id=7]; text=Customer Jane, Vokiečių g. 1",
        )

        assertTrue(sanitized.contains("shape[channel=orders,id=7]"))
        assertTrue(sanitized.contains("text=[redacted]"))
        assertFalse(sanitized.contains("Jane"))
        assertFalse(sanitized.contains("Vokiečių"))
    }

    @Test
    fun addressAndGpsStagesDoNotUploadTheirDetails() {
        assertTrue(
            RemoteDiagnosticsPrivacy.sanitize("address_memory", "Vokiečių g. 1") == "details redacted"
        )
        assertTrue(
            RemoteDiagnosticsPrivacy.sanitize("gps_trace_store_failed", "54.6872,25.2797") == "details redacted"
        )
    }

    @Test
    fun obviousIdentifiersAreRedactedFromGenericTechnicalMessages() {
        val sanitized = RemoteDiagnosticsPrivacy.sanitize(
            "debug",
            "user@example.com +370 612 34567 at 54.687200,25.279700",
        )

        assertFalse(sanitized.contains("user@example.com"))
        assertFalse(sanitized.contains("370 612"))
        assertFalse(sanitized.contains("54.687200"))
        assertTrue(sanitized.contains("[email]"))
        assertTrue(sanitized.contains("[phone]") || sanitized.contains("[coords]"))
    }
}
