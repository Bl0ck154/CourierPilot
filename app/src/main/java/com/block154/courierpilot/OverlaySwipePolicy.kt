package com.block154.courierpilot

import kotlin.math.abs
import kotlin.math.max

/**
 * A deliberate long swipe or a short directional fling can dismiss the card.
 * A fast reversal cannot dismiss it: velocity and displacement must agree.
 */
internal object OverlaySwipePolicy {
    fun shouldDismiss(dxPx: Float, vxPxPerSec: Float, widthPx: Int, density: Float): Boolean {
        val scale = density.coerceAtLeast(0.1f)
        if (abs(dxPx) >= max(56f * scale, 0.30f * widthPx.coerceAtLeast(1))) return true
        return abs(vxPxPerSec) >= 900f * scale &&
            abs(dxPx) >= 16f * scale &&
            ((dxPx > 0f && vxPxPerSec > 0f) || (dxPx < 0f && vxPxPerSec < 0f))
    }
}
