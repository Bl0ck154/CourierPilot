package com.block154.courierpilot

import kotlin.math.abs
import kotlin.math.max

internal enum class OverlayGestureAxis { HORIZONTAL, VERTICAL }

/**
 * Moving the live card is the primary gesture; horizontal swipe-to-dismiss is secondary.
 * A tiny sideways wobble at the beginning of a vertical drag must therefore not permanently
 * capture the gesture as a dismiss swipe.
 */
internal object OverlayGestureAxisPolicy {
    private const val HORIZONTAL_DOMINANCE_RATIO = 1.35f

    fun classify(dx: Float, dy: Float, touchSlop: Int): OverlayGestureAxis? {
        val x = abs(dx)
        val y = abs(dy)
        if (max(x, y) <= touchSlop) return null
        return if (x >= y * HORIZONTAL_DOMINANCE_RATIO) {
            OverlayGestureAxis.HORIZONTAL
        } else {
            OverlayGestureAxis.VERTICAL
        }
    }
}
