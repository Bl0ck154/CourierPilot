package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Looks only at the foreground courier application's top controls. The bounded walk protects
 * touch responsiveness on large Bolt/Wolt accessibility trees, and is cached for one attachment.
 */
internal class OverlayObstacleFinder(private val service: AccessibilityService) {
    private var resolved = false
    private var cachedBottom: Int? = null

    fun reset() {
        resolved = false
        cachedBottom = null
    }

    fun topClickableBottomPx(screenHeightPx: Int): Int? {
        if (resolved) return cachedBottom
        val deadline = SystemClock.uptimeMillis() + WALK_BUDGET_MS
        val limit = (screenHeightPx * 0.30f).toInt()
        var largestBottom: Int? = null
        var inspected = 0
        val roots = runCatching {
            service.windows.orEmpty().mapNotNull { it.root }.toMutableList().apply {
                service.rootInActiveWindow?.let(::add)
            }
        }.getOrDefault(mutableListOf())

        for (root in roots) {
            if (!isCourierPackage(root.packageName?.toString())) continue
            val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            stack.add(root to 0)
            while (stack.isNotEmpty() && inspected < MAX_NODES && SystemClock.uptimeMillis() < deadline) {
                val (node, depth) = stack.removeLast()
                inspected++
                if (!node.isVisibleToUser) continue
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (node.isClickable && bounds.width() > 0 &&
                    bounds.top >= 0 && bounds.top < limit &&
                    bounds.bottom in 1..limit
                ) {
                    largestBottom = maxOf(largestBottom ?: 0, bounds.bottom)
                }
                if (depth >= MAX_DEPTH) continue
                for (i in node.childCount - 1 downTo 0) {
                    node.getChild(i)?.let { stack.add(it to depth + 1) }
                }
            }
            if (inspected >= MAX_NODES || SystemClock.uptimeMillis() >= deadline) break
        }
        resolved = true
        cachedBottom = largestBottom
        return largestBottom
    }

    private fun isCourierPackage(packageName: String?): Boolean {
        val name = packageName?.lowercase() ?: return false
        return name.contains("bolt") || name.contains("wolt")
    }

    private companion object {
        const val MAX_NODES = 400
        const val MAX_DEPTH = 25
        const val WALK_BUDGET_MS = 4L
    }
}
