package com.block154.courierpilot

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Screen-space rules shared by the overlay window and its placement tests.
 * Narrower cards must still fit compact displays without covering the courier app's controls.
 */
internal object OverlayGeometryPolicy {
    fun widthPx(screenWidthPx: Int, density: Float): Int {
        val scale = density.coerceAtLeast(0.1f)
        val base = (screenWidthPx - (24f * scale).roundToInt()).coerceAtLeast(1)
        val minimum = (280f * scale).roundToInt()
        val maximum = (400f * scale).roundToInt()
        return (base * 0.85f).roundToInt().coerceIn(minimum, maximum).coerceAtMost(base)
    }

    fun defaultYPx(
        obstacleBottomPx: Int?,
        topInsetPx: Int,
        screenHeightPx: Int,
        density: Float,
    ): Int {
        val scale = density.coerceAtLeast(0.1f)
        val minimum = topInsetPx.coerceAtLeast(0) + (4f * scale).roundToInt()
        val ceiling = (screenHeightPx * 0.35f).roundToInt().coerceAtLeast(minimum)
        val preferred = if (obstacleBottomPx != null && obstacleBottomPx > 0) {
            obstacleBottomPx + (8f * scale).roundToInt()
        } else {
            max(
                topInsetPx + (8f * scale).roundToInt(),
                (48f * scale + 0.05f * screenHeightPx).roundToInt(),
            )
        }
        return preferred.coerceIn(minimum, ceiling)
    }
}
