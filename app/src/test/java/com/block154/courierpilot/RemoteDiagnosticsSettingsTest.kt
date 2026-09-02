package com.block154.courierpilot

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteDiagnosticsSettingsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("courierpilot_remote_diagnostics", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences("courierpilot_remote_diagnostics_queue", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun togglePersistsSynchronouslyInBothDirections() {
        assertFalse(RemoteDiagnostics.enabled(context))

        assertTrue(RemoteDiagnostics.setEnabled(context, true))
        assertTrue(RemoteDiagnostics.enabled(context))

        assertTrue(RemoteDiagnostics.setEnabled(context, false))
        assertFalse(RemoteDiagnostics.enabled(context))
    }
}
