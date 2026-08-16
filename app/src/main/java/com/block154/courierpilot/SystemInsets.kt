package com.block154.courierpilot

import android.view.View
import android.view.WindowInsets

/** Applies Android system-UI safe-area padding while preserving the view's existing padding. */
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
        val safeArea = insets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        view.setPadding(
            baseLeft + if (left) safeArea.left else 0,
            baseTop + if (top) safeArea.top else 0,
            baseRight + if (right) safeArea.right else 0,
            baseBottom + if (bottom) safeArea.bottom else 0,
        )
        insets
    }
    requestApplyInsets()
}
