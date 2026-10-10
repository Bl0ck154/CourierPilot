package com.block154.courierpilot

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.block154.courierpilot.ui.CourierPilotTheme
import com.block154.courierpilot.ui.DetailHeader
import com.block154.courierpilot.ui.GroupedBlock
import com.block154.courierpilot.ui.GroupedRow
import com.block154.courierpilot.ui.LocalCourierPalette
import com.block154.courierpilot.ui.SectionLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RouteTraceActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var ui by mutableStateOf(RouteTraceUi())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 2_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshStatus()
        setContent {
            CourierPilotTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RouteTraceScreen(
                        ui = ui,
                        onBack = ::finish,
                        onStart = ::requestStartTrace,
                        onStop = ::stopTrace,
                        onShare = ::shareLatest,
                        onDeleteLatest = ::confirmDeleteLatest,
                        onDeleteAll = ::confirmDeleteAll,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_LOCATION -> {
                if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) ensureVisibleNotificationThenStart()
                else refreshStatus("Location permission is required to record a route trace.")
            }
            REQUEST_NOTIFICATIONS -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startTrace()
                else refreshStatus("Notification permission is required so GPS recording remains visibly controllable.")
            }
        }
    }

    private fun requestStartTrace() {
        if (!RouteResearchLocation.hasPermission(this)) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQUEST_LOCATION,
            )
            return
        }
        ensureVisibleNotificationThenStart()
    }

    private fun ensureVisibleNotificationThenStart() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        startTrace()
    }

    private fun startTrace() {
        val status = GpsTraceState.status(this)
        if (status.recording) {
            refreshStatus("A route trace is already recording.")
            return
        }
        runCatching {
            startForegroundService(Intent(this, GpsTraceService::class.java).setAction(GpsTraceService.ACTION_START))
        }.onFailure {
            refreshStatus("Could not start route trace: ${it.javaClass.simpleName}")
            return
        }
        refreshStatus("Starting route trace…")
    }

    private fun stopTrace() {
        runCatching {
            startService(Intent(this, GpsTraceService::class.java).setAction(GpsTraceService.ACTION_STOP))
        }
        refreshStatus("Stopping route trace…")
    }

    private fun refreshStatus(override: String? = null) {
        val state = GpsTraceState.status(this)
        val status = override ?: when {
            state.recording -> buildString {
                append("RECORDING")
                state.startedAt?.let { append(" · started ${formatTime(it)}") }
            }
            state.stale -> "Previous recorder heartbeat is stale. Starting a new trace will close that open DB session first."
            else -> "Not recording."
        }
        val live = if (state.recording) {
            TraceMetrics(
                points = state.sampleCount.toString(),
                distance = "${"%.2f".format(Locale.US, state.distanceMeters / 1000.0)} km",
                third = state.lastSampleAt?.let { "${secondsAgo(it)}s ago" } ?: "—",
            )
        } else {
            null
        }

        val latest = RouteResearchDatabase.get(this).latestGpsSessionSummary()
        ui = if (latest == null) {
            RouteTraceUi(
                status = status,
                tone = if (state.recording) TraceTone.RECORDING else if (state.stale) TraceTone.STALE else TraceTone.IDLE,
                live = live,
                startEnabled = !state.recording,
                stopEnabled = state.recording || state.stale,
                latestTitle = "No trace yet.",
                latestSubtitle = "",
                latestMetrics = null,
                shareEnabled = false,
                deleteLatestEnabled = false,
                deleteAllEnabled = false,
            )
        } else {
            val finished = latest.endedAt != null
            RouteTraceUi(
                status = status,
                tone = if (state.recording) TraceTone.RECORDING else if (state.stale) TraceTone.STALE else TraceTone.IDLE,
                live = live,
                startEnabled = !state.recording,
                stopEnabled = state.recording || state.stale,
                latestTitle = "Session #${latest.sessionId}",
                latestSubtitle = "${formatDateTime(latest.startedAt)} · ${if (finished) "finished" else "open"}",
                latestMetrics = TraceMetrics(
                    points = latest.sampleCount.toString(),
                    distance = "${"%.2f".format(Locale.US, latest.distanceMeters / 1000.0)} km",
                    third = latest.averageSpeedMetersPerSecond?.let { "${"%.1f".format(Locale.US, it * 3.6)} km/h" } ?: "—",
                ),
                shareEnabled = latest.sampleCount >= 2,
                deleteLatestEnabled = finished && !state.recording,
                deleteAllEnabled = !state.recording,
            )
        }
    }

    private fun shareLatest() {
        val db = RouteResearchDatabase.get(this)
        val id = db.latestGpsSessionId() ?: return
        val points = db.gpsSamples(id)
        if (points.size < 2) return
        val body = buildString {
            appendLine("CourierPilot GPS route trace #$id")
            appendLine("Samples: ${points.size}")
            appendLine()
            append(GpsTraceDetailedExport.geoJson(id, points))
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/geo+json"
            putExtra(Intent.EXTRA_SUBJECT, "CourierPilot route trace #$id")
            putExtra(Intent.EXTRA_TEXT, body)
        }, "Share private GPS trace"))
    }

    private fun confirmDeleteLatest() {
        val db = RouteResearchDatabase.get(this)
        val latest = db.latestGpsSessionSummary() ?: return
        if (latest.endedAt == null) return
        AlertDialog.Builder(this)
            .setTitle("Delete latest trace?")
            .setMessage("Delete session #${latest.sessionId} and all of its stored GPS points from this device?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val deleted = db.deleteGpsSession(latest.sessionId)
                refreshStatus(if (deleted) "Deleted route trace #${latest.sessionId}." else "Trace was not deleted.")
            }
            .show()
    }

    private fun confirmDeleteAll() {
        if (GpsTraceState.status(this).recording) return
        AlertDialog.Builder(this)
            .setTitle("Delete all finished traces?")
            .setMessage("This permanently removes every finished GPS route-learning session and its points from this device. An active trace is never deleted by this action.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete all") { _, _ ->
                val deleted = RouteResearchDatabase.get(this).deleteAllFinishedGpsSessions()
                refreshStatus("Deleted $deleted finished trace${if (deleted == 1) "" else "s"}.")
            }
            .show()
    }

    private fun secondsAgo(timestamp: Long): Long = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L) / 1000L)

    private fun formatTime(timestamp: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    private fun formatDateTime(timestamp: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

    companion object {
        private const val REQUEST_LOCATION = 1713
        private const val REQUEST_NOTIFICATIONS = 1714
    }
}

private enum class TraceTone { IDLE, RECORDING, STALE }

private data class TraceMetrics(val points: String, val distance: String, val third: String)

private data class RouteTraceUi(
    val status: String = "Checking…",
    val tone: TraceTone = TraceTone.IDLE,
    val live: TraceMetrics? = null,
    val startEnabled: Boolean = false,
    val stopEnabled: Boolean = false,
    val latestTitle: String = "No trace yet.",
    val latestSubtitle: String = "",
    val latestMetrics: TraceMetrics? = null,
    val shareEnabled: Boolean = false,
    val deleteLatestEnabled: Boolean = false,
    val deleteAllEnabled: Boolean = false,
)

@Composable
private fun RouteTraceScreen(
    ui: RouteTraceUi,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onShare: () -> Unit,
    onDeleteLatest: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    val palette = LocalCourierPalette.current
    val error = MaterialTheme.colorScheme.error
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
    ) {
        item { DetailHeader("Ride trace", "Explicit GPS recording for route learning", onBack) }

        item { SectionLabel("Current recording") }
        item {
            GroupedBlock {
                Column(
                    Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (ui.tone != TraceTone.IDLE) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (ui.tone == TraceTone.RECORDING) palette.onlineText else palette.rateFire,
                                modifier = Modifier.size(9.dp),
                            ) {}
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(
                            ui.status,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = when (ui.tone) {
                                TraceTone.RECORDING -> palette.onlineText
                                TraceTone.STALE -> palette.rateFire
                                TraceTone.IDLE -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    ui.live?.let { TraceMetricRow(it, "Last fix") }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onStart,
                            enabled = ui.startEnabled,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Start ride trace", fontWeight = FontWeight.Bold)
                        }
                        FilledTonalButton(
                            onClick = onStop,
                            enabled = ui.stopEnabled,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Stop trace", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        item { SectionLabel("Latest local trace") }
        item {
            GroupedRow(index = 0, count = 4) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(ui.latestTitle, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        if (ui.latestSubtitle.isNotBlank()) {
                            Text(ui.latestSubtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, lineHeight = 17.sp)
                        }
                    }
                    ui.latestMetrics?.let { TraceMetricRow(it, "Avg speed") }
                }
            }
        }
        item { TraceActionRow(1, 4, "Share latest as GeoJSON", ui.shareEnabled, palette.accent, onShare) }
        item { TraceActionRow(2, 4, "Delete latest finished trace", ui.deleteLatestEnabled, error, onDeleteLatest) }
        item { TraceActionRow(3, 4, "Delete all finished traces", ui.deleteAllEnabled, error, onDeleteAll) }

        item { SectionLabel("How this works") }
        item {
            TraceTextBlock(
                "Start creates one local route-learning session and a visible foreground-service notification. You can leave CourierPilot while it records. Stop ends the session; the service is not silently restarted after a kill or reboot.",
                "Default sampling request: about every 2 seconds / 2 meters. Points worse than ±80 m accuracy and extreme GPS jumps are ignored.",
            )
        }

        item { SectionLabel("Privacy / scope") }
        item {
            TraceTextBlock(
                "Raw GPS samples stay in route_research.db. Recording begins only from this visible screen and remains visibly represented by Android's foreground-service notification. Rich GeoJSON export includes point timestamps, accuracy and reported speed. Finished traces can be deleted here. 0.11 does not upload traces or map-match them automatically.",
            )
        }
    }
}

@Composable
private fun TraceMetricRow(metrics: TraceMetrics, thirdLabel: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TraceMetric("Points", metrics.points, Modifier.weight(1f))
        TraceMetric("Distance", metrics.distance, Modifier.weight(1f))
        TraceMetric(thirdLabel, metrics.third, Modifier.weight(1f))
    }
}

@Composable
private fun TraceMetric(label: String, value: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = LocalCourierPalette.current.miniStatBg) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 1)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
    }
}

/** Grouped-list action that greys out instead of disappearing when it cannot run. */
@Composable
private fun TraceActionRow(index: Int, count: Int, text: String, enabled: Boolean, color: Color, onClick: () -> Unit) {
    GroupedRow(index = index, count = count, onClick = if (enabled) onClick else null) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = if (enabled) color else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun TraceTextBlock(vararg paragraphs: String) {
    GroupedBlock {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            paragraphs.forEach {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
        }
    }
}
