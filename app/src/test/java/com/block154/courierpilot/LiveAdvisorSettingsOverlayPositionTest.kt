package com.block154.courierpilot

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LiveAdvisorSettingsOverlayPositionTest {
    @Test
    fun legacyDragPositionIsIgnoredAndV2PositionPersists() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("courierpilot_live_advisor", Context.MODE_PRIVATE)
            .edit().putInt("overlay_y_px", 12).putInt("overlay_y_version", 2).commit()

        assertNull(LiveAdvisorSettings.overlayYPx(context))
        LiveAdvisorSettings.setOverlayYPx(context, 154)
        assertEquals(154, LiveAdvisorSettings.overlayYPx(context))
    }
}
