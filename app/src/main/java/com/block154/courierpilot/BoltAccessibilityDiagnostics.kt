package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

internal data class BoltResearchSampleSummary(
    val capturedAt: Long,
    val nodeCount: Int,
    val truncated: Boolean,
    val screenshotAvailable: Boolean,
    val locationAvailable: Boolean,
    val locationAgeMillis: Long?,
)

/** One-shot private Bolt research bundle: tree + screenshot + metadata + best available cached GPS. */
internal object BoltAccessibilityDiagnostics {
    private const val PREFS = "bolt_accessibility_diagnostics"
    private const val KEY_ARMED = "armed"
    private const val KEY_CAPTURED_AT = "captured_at"
    private const val KEY_NODE_COUNT = "node_count"
    private const val KEY_TRUNCATED = "truncated"
    private const val KEY_SCREENSHOT = "screenshot"
    private const val KEY_LOCATION = "location"
    private const val KEY_LOCATION_AGE = "location_age"
    private const val SAMPLE_DIR = "bolt-research"
    const val TREE_FILE = "accessibility-tree.txt"
    const val SCREENSHOT_FILE = "screen.png"
    const val METADATA_FILE = "metadata.json"

    fun arm(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ARMED, true).apply()
    }

    fun disarm(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ARMED, false).apply()
    }

    fun isArmed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ARMED, false)

    /** Consumes one arm action immediately so repeated Accessibility events cannot create duplicates. */
    fun consumeArm(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ARMED, false)) return false
        prefs.edit().putBoolean(KEY_ARMED, false).commit()
        return true
    }

    fun saveSample(
        context: Context,
        tree: SerializedAccessibilityTree,
        bitmap: Bitmap?,
        screenshotError: String?,
        location: CurrentLocationFix?,
    ) {
        val capturedAt = System.currentTimeMillis()
        val dir = sampleDir(context).apply { deleteRecursively(); mkdirs() }
        File(dir, TREE_FILE).writeText(tree.text)
        val screenshotSaved = bitmap?.let { image ->
            runCatching {
                File(dir, SCREENSHOT_FILE).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }.getOrDefault(false)
        } ?: false
        val metadata = JSONObject()
            .put("captured_at", capturedAt)
            .put("package", CourierSignals.BOLT_PACKAGE)
            .put("node_count", tree.nodeCount)
            .put("tree_truncated", tree.truncated)
            .put("screenshot_available", screenshotSaved)
            .put("screenshot_error", screenshotError ?: JSONObject.NULL)
            .put("screen_width", bitmap?.width ?: JSONObject.NULL)
            .put("screen_height", bitmap?.height ?: JSONObject.NULL)
        if (location != null) {
            metadata.put("location", JSONObject()
                .put("lat", location.point.latitude)
                .put("lon", location.point.longitude)
                .put("accuracy_m", location.accuracyMeters ?: JSONObject.NULL)
                .put("age_ms", location.ageMillis)
                .put("provider", location.provider))
        } else {
            metadata.put("location", JSONObject.NULL)
        }
        File(dir, METADATA_FILE).writeText(metadata.toString(2))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_CAPTURED_AT, capturedAt)
            .putInt(KEY_NODE_COUNT, tree.nodeCount)
            .putBoolean(KEY_TRUNCATED, tree.truncated)
            .putBoolean(KEY_SCREENSHOT, screenshotSaved)
            .putBoolean(KEY_LOCATION, location != null)
            .putLong(KEY_LOCATION_AGE, location?.ageMillis ?: -1L)
            .apply()
    }

    fun summary(context: Context): BoltResearchSampleSummary? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val capturedAt = prefs.getLong(KEY_CAPTURED_AT, 0L)
        if (capturedAt <= 0L || !File(sampleDir(context), TREE_FILE).exists()) return null
        return BoltResearchSampleSummary(
            capturedAt = capturedAt,
            nodeCount = prefs.getInt(KEY_NODE_COUNT, 0),
            truncated = prefs.getBoolean(KEY_TRUNCATED, false),
            screenshotAvailable = prefs.getBoolean(KEY_SCREENSHOT, false),
            locationAvailable = prefs.getBoolean(KEY_LOCATION, false),
            locationAgeMillis = prefs.getLong(KEY_LOCATION_AGE, -1L).takeIf { it >= 0 },
        )
    }

    fun sampleFiles(context: Context): List<File> = listOf(TREE_FILE, METADATA_FILE, SCREENSHOT_FILE)
        .map { File(sampleDir(context), it) }
        .filter(File::exists)

    fun readLastDump(context: Context): String? = File(sampleDir(context), TREE_FILE).takeIf(File::exists)?.readText()

    fun clear(context: Context) {
        sampleDir(context).deleteRecursively()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun sampleDir(context: Context) = File(File(context.filesDir, "diagnostics"), SAMPLE_DIR)
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
            if (nodeCount >= MAX_NODES || out.length >= MAX_CHARS) { truncated = true; break }
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

class BoltAccessibilityDiagnosticsService : AccessibilityService() {
    private var captureInFlight = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        CaptureEventLog.append(this, "bolt_diag_service", "Bolt research service connected", "Bolt", 30_000L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (captureInFlight || !BoltAccessibilityDiagnostics.isArmed(this)) return
        if (event?.packageName?.toString() != CourierSignals.BOLT_PACKAGE) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != CourierSignals.BOLT_PACKAGE) return
        if (!BoltAccessibilityDiagnostics.consumeArm(this)) return

        captureInFlight = true
        val tree = runCatching { BoltAccessibilityTreeSerializer.serialize(root) }.getOrElse { error ->
            captureInFlight = false
            CaptureEventLog.append(this, "bolt_sample_failed", error.javaClass.simpleName, "Bolt")
            return
        }
        val location = RouteResearchLocation.bestLastKnown(this)
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val bitmap = screenshotToBitmap(screenshot)
                BoltAccessibilityDiagnostics.saveSample(
                    this@BoltAccessibilityDiagnosticsService,
                    tree,
                    bitmap,
                    if (bitmap == null) "Screenshot buffer conversion failed" else null,
                    location,
                )
                bitmap?.recycle()
                captureInFlight = false
                CaptureEventLog.append(this@BoltAccessibilityDiagnosticsService, "bolt_sample_saved", "Saved tree + ${if (bitmap != null) "screenshot" else "no screenshot"} + ${if (location != null) "cached GPS" else "no GPS"}", "Bolt")
            }

            override fun onFailure(errorCode: Int) {
                BoltAccessibilityDiagnostics.saveSample(this@BoltAccessibilityDiagnosticsService, tree, null, "Android screenshot error $errorCode", location)
                captureInFlight = false
                CaptureEventLog.append(this@BoltAccessibilityDiagnosticsService, "bolt_sample_saved", "Saved tree; screenshot failed with Android error $errorCode", "Bolt")
            }
        })
    }

    override fun onInterrupt() = Unit

    private fun screenshotToBitmap(screenshot: ScreenshotResult): Bitmap? {
        val buffer = screenshot.hardwareBuffer
        return try {
            val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) ?: return null
            try { hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false) } finally { hardwareBitmap.recycle() }
        } catch (_: Throwable) {
            null
        } finally {
            buffer.close()
        }
    }
}
