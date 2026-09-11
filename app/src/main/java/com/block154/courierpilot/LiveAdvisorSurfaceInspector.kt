package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.Locale

internal data class LiveAdvisorSurfaceInspection(
    val text: String,
    val snapshot: LiveOfferSurfaceSnapshot,
)

/**
 * Reads the currently visible courier surface for advisor lifetime decisions.
 *
 * This class intentionally does not decide whether an offer should live, hide or die. It only
 * locates/refreshed Accessibility roots and turns their visible nodes into text plus a stable
 * structural snapshot. [StableLiveOfferAdvisor] remains the state-machine owner.
 */
internal class LiveAdvisorSurfaceInspector(
    private val service: AccessibilityService,
) {
    private var lastSlowSurfaceLogAtElapsed = 0L

    fun findVisiblePackageRoot(packageName: String): AccessibilityNodeInfo? {
        fun refreshed(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            val candidate = node ?: return null
            if (candidate.packageName?.toString() != packageName) return null
            val valid = runCatching { candidate.refresh() }.getOrDefault(false)
            if (!valid || candidate.packageName?.toString() != packageName) return null
            return candidate
        }

        refreshed(service.rootInActiveWindow)?.let { return it }
        service.windows.forEach { window ->
            val candidate = runCatching { window.root }.getOrNull()
            refreshed(candidate)?.let { return it }
        }
        return null
    }

    fun inspectVisibleSurface(
        rootNode: AccessibilityNodeInfo,
        platform: String,
    ): LiveAdvisorSurfaceInspection {
        val startedAtElapsed = SystemClock.elapsedRealtime()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val pieces = mutableListOf<String>()
        val interactiveSlots = linkedSetOf<String>()
        val screenWidth = service.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val screenHeight = service.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val bounds = Rect()
        queue.add(rootNode)
        var visited = 0
        var visibleNodes = 0
        var leafNodes = 0
        var bottomNodes = 0

        while (queue.isNotEmpty() && visited < MAX_SURFACE_NODES) {
            val node = queue.removeFirst()
            visited += 1
            val childCount = node.childCount
            for (index in 0 until childCount) node.getChild(index)?.let(queue::addLast)

            if (!runCatching { node.isVisibleToUser }.getOrDefault(true)) continue
            visibleNodes += 1
            if (childCount == 0) leafNodes += 1

            listOf(node.text, node.contentDescription).forEach { value ->
                val cleaned = value?.toString()?.trim().orEmpty()
                if (cleaned.isNotEmpty() && pieces.lastOrNull() != cleaned) pieces += cleaned
            }

            bounds.setEmpty()
            runCatching { node.getBoundsInScreen(bounds) }
            val centerY = bounds.centerY()
            if (centerY >= (screenHeight * 55 / 100)) bottomNodes += 1

            val interactive = runCatching { node.isClickable || node.isLongClickable }.getOrDefault(false)
            if (interactive && !bounds.isEmpty) {
                val className = node.className?.toString()?.substringAfterLast('.') ?: "node"
                val centerXBin = bounds.centerX().coerceIn(0, screenWidth) * 20 / screenWidth
                val centerYBin = bounds.centerY().coerceIn(0, screenHeight) * 20 / screenHeight
                val widthBin = (bounds.width().coerceAtLeast(0) * 20 / screenWidth).coerceAtMost(20)
                val heightBin = (bounds.height().coerceAtLeast(0) * 20 / screenHeight).coerceAtMost(20)
                interactiveSlots += "$className:$centerXBin:$centerYBin:$widthBin:$heightBin"
            }
        }

        val inspection = LiveAdvisorSurfaceInspection(
            text = pieces.joinToString("\n"),
            snapshot = LiveOfferSurfaceSnapshot(
                windowId = rootNode.windowId,
                nodeCount = visibleNodes,
                leafCount = leafNodes,
                bottomNodeCount = bottomNodes,
                interactiveSlots = interactiveSlots,
                stableLines = LiveOfferSurfaceEvidence.normalizeStableLines(pieces),
            ),
        )
        val finishedAtElapsed = SystemClock.elapsedRealtime()
        val durationMs = (finishedAtElapsed - startedAtElapsed).coerceAtLeast(0L)
        if (durationMs >= SLOW_SURFACE_SCAN_MS &&
            finishedAtElapsed - lastSlowSurfaceLogAtElapsed >= SLOW_SURFACE_LOG_INTERVAL_MS
        ) {
            lastSlowSurfaceLogAtElapsed = finishedAtElapsed
            CaptureEventLog.append(
                service,
                stage = "overlay_tree_slow",
                platform = platform,
                message = "duration_ms=$durationMs; visited=$visited; visible=$visibleNodes; leaves=$leafNodes; " +
                    "text_items=${pieces.size}; capped=${visited >= MAX_SURFACE_NODES}",
            )
        }
        return inspection
    }

    fun hasDecisionPair(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val accept = listOf("accept", "priimti", "принять", "прийняти").any(lower::contains)
        val decline = listOf("decline", "reject", "atmesti", "отклонить", "відхилити").any(lower::contains)
        return accept && decline
    }

    private companion object {
        const val MAX_SURFACE_NODES = 600
        const val SLOW_SURFACE_SCAN_MS = 32L
        const val SLOW_SURFACE_LOG_INTERVAL_MS = 5_000L
    }
}
