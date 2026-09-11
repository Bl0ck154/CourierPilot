package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/** A currently reachable courier Accessibility window. */
internal data class CourierWindow(
    val root: AccessibilityNodeInfo,
    val windowId: Int,
    val packageName: String,
)

/**
 * Low-level Accessibility window and tree operations used by offer capture.
 *
 * Keep this component deliberately policy-free: it only discovers courier windows, projects text
 * from Accessibility nodes and performs explicit clicks requested by the capture orchestrator.
 * Offer identity, parser compatibility, Wolt/Bolt lifetime rules and persistence remain owned by
 * [OfferAccessibilityService].
 */
internal class OfferAccessibilitySurface(
    private val service: AccessibilityService,
) {
    fun findAnyCourierWindow(): CourierWindow? {
        val active = service.rootInActiveWindow
        val activePackage = active?.packageName?.toString().orEmpty()
        if (active != null && CourierSignals.isCourierPackage(activePackage)) {
            return CourierWindow(active, active.windowId, activePackage)
        }
        service.windows.forEach { window ->
            val root = runCatching { window.root }.getOrNull() ?: return@forEach
            val pkg = root.packageName?.toString().orEmpty()
            if (CourierSignals.isCourierPackage(pkg)) return CourierWindow(root, window.id, pkg)
        }
        return null
    }

    fun findCourierWindow(packageName: String): CourierWindow? {
        val active = service.rootInActiveWindow
        if (active?.packageName?.toString() == packageName) {
            return CourierWindow(active, active.windowId, packageName)
        }
        service.windows.forEach { window ->
            val root = runCatching { window.root }.getOrNull() ?: return@forEach
            if (root.packageName?.toString() == packageName) {
                return CourierWindow(root, window.id, packageName)
            }
        }
        return null
    }

    /** Historical full-tree projection, including non-visible Compose semantics. */
    fun collectAllText(root: AccessibilityNodeInfo): String =
        collectPieces(root, visibleFilter = null).joinToString("\n")

    fun collectStrictlyVisibleText(root: AccessibilityNodeInfo): String =
        collectStrictlyVisiblePieces(root).joinToString("\n")

    fun collectStrictlyVisiblePieces(root: AccessibilityNodeInfo): List<String> =
        collectPieces(root, visibleFilter = true)

    fun collectHiddenPieces(root: AccessibilityNodeInfo): List<String> =
        collectPieces(root, visibleFilter = false)

    fun collectPieces(
        root: AccessibilityNodeInfo,
        visibleFilter: Boolean?,
    ): List<String> {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val pieces = mutableListOf<String>()
        queue.add(root)
        var visited = 0

        fun addPiece(value: CharSequence?, eligible: Boolean) {
            if (!eligible) return
            val cleaned = value?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return
            if (pieces.lastOrNull() != cleaned) pieces += cleaned
        }

        while (queue.isNotEmpty() && visited < MAX_TREE_NODES) {
            val node = queue.removeFirst()
            visited += 1
            val eligible = visibleFilter == null || node.isVisibleToUser == visibleFilter
            addPiece(node.text, eligible)
            addPiece(node.contentDescription, eligible)
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return pieces
    }

    fun hasVisibleText(
        root: AccessibilityNodeInfo,
        matches: (String) -> Boolean,
    ): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_TREE_NODES) {
            val node = queue.removeFirst()
            visited += 1
            val values = nodeValues(node)
            if (node.isVisibleToUser && values.any(matches)) return true
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return false
    }

    fun hasVisibleClickableText(
        root: AccessibilityNodeInfo,
        matches: (String) -> Boolean,
    ): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_TREE_NODES) {
            val node = queue.removeFirst()
            visited += 1
            val values = nodeValues(node)
            if (node.isVisibleToUser && values.any(matches)) {
                var candidate: AccessibilityNodeInfo? = node
                repeat(6) {
                    val current = candidate ?: return@repeat
                    if (current.isVisibleToUser && current.isClickable && current.isEnabled) return true
                    candidate = current.parent
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return false
    }

    fun clickVisibleText(
        root: AccessibilityNodeInfo,
        matches: (String) -> Boolean,
    ): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_TREE_NODES) {
            val node = queue.removeFirst()
            visited += 1
            val values = nodeValues(node)
            if (node.isVisibleToUser && values.any(matches)) {
                var clickable: AccessibilityNodeInfo? = node
                repeat(5) {
                    val candidate = clickable ?: return@repeat
                    if (candidate.isClickable && candidate.isEnabled &&
                        candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    ) {
                        return true
                    }
                    clickable = candidate.parent
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return false
    }

    private fun nodeValues(node: AccessibilityNodeInfo): List<String> =
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }

    private companion object {
        const val MAX_TREE_NODES = 700
    }
}
