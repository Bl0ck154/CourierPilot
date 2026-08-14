package com.block154.courierpilot

import android.view.View
import android.view.WindowInsets

/** Applies Android system-bar safe-area padding while preserving the view's existing padding. */
internal fun View.applySystemBarsPadding(
    top: Boolean = true,
    bottom: Boolean = true,
    left: Boolean = false,
    right: Boolean = false,
) {
    val baseLeft = paddingLeft
    val baseTop = paddingTop
    val baseRight = paddingRight
    val baseBottom = paddingBottom
    setOnApplyWindowInsetsListener { view, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars())
        view.setPadding(
            baseLeft + if (left) bars.left else 0,
            baseTop + if (top) bars.top else 0,
            baseRight + if (right) bars.right else 0,
            baseBottom + if (bottom) bars.bottom else 0,
        )
        insets
    }
    requestApplyInsets()
}
