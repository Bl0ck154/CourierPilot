package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveAdvisorOverlayViewSmokeTest {
    class TestService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    @Test
    fun ensureCreatesCompactCardWithoutTitleRowAndDebugStartsHidden() {
        val service = Robolectric.buildService(TestService::class.java).create().get()
        val overlay = LiveAdvisorOverlayView(service, {}, {}, { "Bolt" })
        overlay.ensure()

        val field = LiveAdvisorOverlayView::class.java.getDeclaredField("root").apply {
            isAccessible = true
        }
        val root = field.get(overlay) as? LinearLayout
        assertNotNull(root)
        root!!
        assertEquals(2, root.childCount) // body + optional debug line; no header row
        val texts = ArrayList<TextView>()
        fun walk(view: View) {
            if (view is TextView) texts.add(view)
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }
        walk(root)
        assertFalse(texts.any { it.text.toString().startsWith("CourierPilot ·") })
        assertTrue(texts.any { it.text.toString() == "×" })
        assertTrue(texts.any { it.visibility == View.GONE })
        overlay.applyDebugLines(listOf("pickup", "drop-off"))
        // 0.16.1 moved the developer-only version label from the route line onto the last debug line.
        assertTrue(texts.any {
            it.text.toString() == "pickup\ndrop-off · v${BuildConfig.VERSION_NAME}" && it.visibility == View.VISIBLE
        })
        overlay.applyDebugLines(emptyList())
        assertTrue(texts.any { it.text.toString().isEmpty() && it.visibility == View.GONE })
        overlay.detach(animate = false)
        assertEquals(null, overlay.screenRect())
    }
}
