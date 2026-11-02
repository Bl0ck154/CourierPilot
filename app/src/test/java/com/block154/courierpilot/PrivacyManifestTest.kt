package com.block154.courierpilot

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Private courier history must never be eligible for Android app backup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35])
class PrivacyManifestTest {
    @Test
    fun androidAppBackupIsDisabled() {
        val app = RuntimeEnvironment.getApplication()
        val flags = app.applicationInfo.flags
        assertEquals(0, flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
