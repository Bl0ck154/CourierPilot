package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateVersionTest {
    @Test
    fun newerPatchReleaseIsDetectedNumerically() {
        assertTrue(AppUpdateVersion.isNewer("v0.15.10", "0.15.9"))
        assertTrue(AppUpdateVersion.isNewer("1.0.1", "1.0"))
        assertFalse(AppUpdateVersion.isNewer("0.15.9", "0.15.9"))
        assertFalse(AppUpdateVersion.isNewer("0.15.8", "0.15.9"))
    }

    @Test
    fun releaseSuffixDoesNotBreakNumericVersionComparison() {
        assertEquals("0.15.10", AppUpdateVersion.normalize("v0.15.10+51"))
        assertEquals("0.15.10", AppUpdateVersion.normalize("0.15.10-release"))
        assertNull(AppUpdateVersion.normalize("latest"))
    }

    @Test
    fun parsesSha256FromStandardSha256sumOutput() {
        val hash = "74556417f1289281bcaf1a2c6f3f4aa119db24b079a13759a583c3cc66796b70"
        assertEquals(
            hash,
            AppUpdateIntegrity.parseSha256("$hash  CourierPilot-v0.15.10.apk\n"),
        )
    }

    @Test
    fun rejectsMalformedChecksum() {
        assertNull(AppUpdateIntegrity.parseSha256("not-a-checksum CourierPilot-v0.15.10.apk"))
    }

}
