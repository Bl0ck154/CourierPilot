package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

internal data class BoltTreeDumpSummary(
    val capturedAt: Long,
    val nodeCount: Int,
    val truncated: Boolean,
)

/**
 * Explicit, one-shot diagnostic capture for the Bolt offer screen.
 *
 * This is intentionally separate from the production offer-capture service. A user must enable the
 * diagnostic Accessibility service and arm one dump from Reliability before anything is written.
 * The dump may contain customer/merchant text, so it stays in app-internal storage until the user
 * explicitly shares it.
 */
internal object BoltAccessibilityDiagnostics {
    private const val PREFS = "bolt_accessibility_diagnostics"
    private const val KEY_ARMED = "armed"
    private const val KEY_CAPTURED_AT = "captured_at"
    private const val KEY_NODE_COUNT = "node_count"
    private const val KEY_TRUNCATED = "truncated"
    private const val FILE_NAME = "bolt-accessibility-tree.txt"

    fun arm(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ARMED, true)
            .apply()
    }

    fun disarm(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ARMED, false)
            .apply()
    }

    fun isArmed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ARMED, false)

    fun save(context: Context, text: String, nodeCount: Int, truncated: Boolean) {
        val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        File(dir, FILE_NAME).writeText(text)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ARMED, false)
            .putLong(KEY_CAPTURED_AT, System.currentTimeMillis())
            .putInt(KEY_NODE_COUNT, nodeCount)
            .putBoolean(KEY_TRUNCATED, truncated)
            .apply()
    }

    fun summary(context: Context): BoltTreeDumpSummary? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val capturedAt = prefs.getLong(KEY_CAPTURED_AT, 0L)
        val file = File(File(context.filesDir, "diagnostics"), FILE_NAME)
        if (capturedAt <= 0L || !file.exists()) return null
        return BoltTreeDumpSummary(
            capturedAt = capturedAt,
            nodeCount = prefs.getInt(KEY_NODE_COUNT, 0),
            truncated = prefs.getBoolean(KEY_TRUNCATED, false),
        )
    }

    fun readLastDump(context: Context): String? {
        val file = File(File(context.filesDir, "diagnostics"), FILE_NAME)
        return file.takeIf(File::exists)?.readText()
    }

    fun clear(context: Context) {
        File(File(context.filesDir, "diagnostics"), FILE_NAME).delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

internal data class SerializedAccessibilityTree(
    val text: String,
    val nodeCount: Int,
    val truncated: Boolean,
)

internal object BoltAccessibilityTreeSerializer {
    private const val MAX_NODES = 1_500
    private const val MAX_CHARS = 180_000
    private const val MAX_FIELD_CHARS = 600

    private data class QueuedNode(val node: AccessibilityNodeInfo, val depth: Int, val index: Int)

    fun serialize(root: AccessibilityNodeInfo): SerializedAccessibilityTree {
        val timestamp = System.currentTimeMillis()
        val out = StringBuilder(16_384)
        out.appendLine("CourierPilot Bolt Accessibility tree")
        out.appendLine("capturedAt=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(timestamp))}")
        out.appendLine("package=${safe { root.packageName?.toString() }}")
        out.appendLine("windowId=${safe { root.windowId.toString() }}")
        out.appendLine("WARNING=May contain merchant/customer text and other private on-screen data. Share deliberately.")
        out.appendLine()

        val queue = ArrayDeque<QueuedNode>()
        queue.add(QueuedNode(root, 0, 0))
        var nextIndex = 1
        var nodeCount = 0
        var truncated = false

        while (queue.isNotEmpty()) {
            if (nodeCount >= MAX_NODES || out.length >= MAX_CHARS) {
                truncated = true
                break
            }

            val item = queue.removeFirst()
            val node = item.node
            val rect = Rect()
            runCatching { node.getBoundsInScreen(rect) }

            out.append("#").append(item.index)
                .append(" depth=").append(item.depth)
                .append(" class=").append(field(safe { node.className?.toString() }))
                .append(" text=").append(field(safe { node.text?.toString() }))
                .append(" desc=").append(field(safe { node.contentDescription?.toString() }))
                .append(" viewId=").append(field(safe { node.viewIdResourceName }))
                .append(" bounds=").append(rect.flattenToString())
                .append(" visible=").append(safeBool { node.isVisibleToUser })
                .append(" clickable=").append(safeBool { node.isClickable })
                .append(" focusable=").append(safeBool { node.isFocusable })
                .append(" scrollable=").append(safeBool { node.isScrollable })
                .append(" enabled=").append(safeBool { node.isEnabled })
                .append(" children=").append(safe { node.childCount.toString() })
                .appendLine()

            nodeCount++
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (childIndex in 0 until childCount) {
                val child = runCatching { node.getChild(childIndex) }.getOrNull() ?: continue
                queue.add(QueuedNode(child, item.depth + 1, nextIndex++))
            }
        }

        if (truncated) out.appendLine("TRUNCATED=true maxNodes=$MAX_NODES maxChars=$MAX_CHARS")
        return SerializedAccessibilityTree(out.toString(), nodeCount, truncated)
    }

    private fun field(value: String?): String {
        if (value.isNullOrBlank()) return "-"
        val normalized = value.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim()
        val clipped = if (normalized.length > MAX_FIELD_CHARS) normalized.take(MAX_FIELD_CHARS) + "…" else normalized
        return clipped.replace("\\", "\\\\").replace("\"", "\\\"").let { "\"$it\"" }
    }

    private fun safe(block: () -> String?): String? = runCatching(block).getOrNull()
    private fun safeBool(block: () -> Boolean): Boolean = runCatching(block).getOrDefault(false)
}

/**
 * Separate research-only service so experimentation cannot alter the production capture state
 * machine. It listens only for Bolt events (the XML config also scopes the package) and writes at
 * most one tree per explicit arm action.
 */
class BoltAccessibilityDiagnosticsService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        CaptureEventLog.append(
            this,
            stage = "bolt_diag_service",
            platform = "Bolt",
            message = "Bolt Accessibility diagnostics service connected",
            dedupeWindowMs = 30_000L,
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!BoltAccessibilityDiagnostics.isArmed(this)) return
        val eventPackage = event?.packageName?.toString().orEmpty()
        if (eventPackage != CourierSignals.BOLT_PACKAGE) return

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != CourierSignals.BOLT_PACKAGE) return

        runCatching {
            val serialized = BoltAccessibilityTreeSerializer.serialize(root)
            BoltAccessibilityDiagnostics.save(this, serialized.text, serialized.nodeCount, serialized.truncated)
            CaptureEventLog.append(
                this,
                stage = "bolt_tree_saved",
                platform = "Bolt",
                message = "Saved one armed Accessibility tree (${serialized.nodeCount} nodes${if (serialized.truncated) ", truncated" else ""})",
            )
        }.onFailure { error ->
            BoltAccessibilityDiagnostics.disarm(this)
            CaptureEventLog.append(
                this,
                stage = "bolt_tree_failed",
                platform = "Bolt",
                message = error.javaClass.simpleName,
            )
        }
    }

    override fun onInterrupt() = Unit
}
