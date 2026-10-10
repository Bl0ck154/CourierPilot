package com.block154.courierpilot

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.block154.courierpilot.ui.ActionRow
import com.block154.courierpilot.ui.CourierPilotTheme
import com.block154.courierpilot.ui.DetailHeader
import com.block154.courierpilot.ui.Footnote
import com.block154.courierpilot.ui.GroupedBlock
import com.block154.courierpilot.ui.GroupedRow
import com.block154.courierpilot.ui.IconTile
import com.block154.courierpilot.ui.LocalCourierPalette
import com.block154.courierpilot.ui.SectionLabel
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Internal/research controls deliberately kept out of normal Settings and Reliability. */
class DeveloperToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!DeveloperModeSettings.enabled(this)) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            CourierPilotTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DeveloperToolsScreen(onBack = ::finish)
                }
            }
        }
    }
}

@Composable
private fun DeveloperToolsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val palette = LocalCourierPalette.current
    val routeReady = runCatching { RouteEndpointSettings.load(context).validated() }.isSuccess
    val boltSample = BoltAccessibilityDiagnostics.summary(context)
    val truthDb = runCatching { RouteResearchDatabase.get(context) }.getOrNull()
    val truthStats = runCatching { truthDb?.boltRecoveryStats() }.getOrNull()
    val truthRows = runCatching { truthDb?.boltRecoveryTruthRows(20).orEmpty() }.getOrDefault(emptyList())
    val count = truthStats?.count ?: 0
    fun metres(value: Double?): String =
        value?.let { String.format(Locale.US, "%.0f m", it) } ?: "—"

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
    ) {
        item { DetailHeader("Developer tools", "Internal diagnostics and route validation", onBack) }

        item { Spacer(Modifier.height(18.dp)) }
        item {
            GroupedRow(index = 0, count = 1) {
                IconTile(Icons.Rounded.BugReport)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Research-only", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "These controls are for CourierPilot development. Normal users do not need server URLs, tokens, raw Accessibility trees or manual coordinates.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        }

        item { SectionLabel("Route research") }
        item {
            DevLinkRow(
                icon = Icons.Rounded.Map,
                title = "Open manual route research",
                subtitle = if (routeReady) "Protected route service configured on this device" else "Route service is not provisioned on this device",
                subtitleColor = if (routeReady) palette.onlineText else MaterialTheme.colorScheme.onSurfaceVariant,
            ) { context.startActivity(Intent(context, RouteResearchActivity::class.java)) }
        }

        item { SectionLabel("Bolt research") }
        item {
            DevLinkRow(
                icon = Icons.Rounded.Settings,
                title = "Open Accessibility services",
                subtitle = boltSample?.let {
                    "Last private sample: ${it.nodeCount} nodes · screenshot ${if (it.screenshotAvailable) "yes" else "no"} · GPS ${if (it.locationAvailable) "yes" else "no"}"
                } ?: "No private Bolt research sample saved",
            ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        item { SectionLabel("Bolt recovery accuracy · local only") }
        item {
            GroupedBlock {
                Column(
                    Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DevMetric("Matched", count.toString(), Modifier.weight(1f))
                        DevMetric("Median", metres(truthStats?.medianMeters), Modifier.weight(1f))
                        DevMetric("p80", metres(truthStats?.p80Meters), Modifier.weight(1f))
                    }
                    if (truthRows.isEmpty()) {
                        Text(
                            "No matched deliveries yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp,
                        )
                    }
                    truthRows.forEach { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("Offer #${item.offerId} · error ${metres(item.errorMeters)}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                "markers ${item.pickupMarkerCount}/${item.dropoffMarkerCount} · ETA ${item.etaMinutes ?: "?"} min",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.5.sp,
                                lineHeight = 17.sp,
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
        item {
            DevActionRow(
                text = "Export private Bolt research (share sheet)",
                enabled = count > 0,
            ) { exportBoltRecoveryTruth(context) }
        }
        item { Footnote("Export includes local coordinates and may include the saved research screenshot/tree. Share only deliberately.") }

        item { Spacer(Modifier.height(22.dp)) }
        item {
            ActionRow(
                index = 0,
                count = 1,
                text = "Disable developer mode",
                color = MaterialTheme.colorScheme.error,
            ) {
                DeveloperModeSettings.setEnabled(context, false)
                onBack()
            }
        }
    }
}

/** Link row whose subtitle may wrap: same anatomy as LinkRow with a compact line height. */
@Composable
private fun DevLinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    subtitleColor: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    GroupedRow(index = 0, count = 1, onClick = onClick) {
        IconTile(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                subtitle,
                color = if (subtitleColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else subtitleColor,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
            )
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Mini stat tile: muted label over an extra-bold value. */
@Composable
private fun DevMetric(label: String, value: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = LocalCourierPalette.current.miniStatBg) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 1)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
    }
}

/** Single-row action that greys out instead of disappearing when it cannot run. */
@Composable
private fun DevActionRow(text: String, enabled: Boolean, onClick: () -> Unit) {
    GroupedRow(index = 0, count = 1, onClick = if (enabled) onClick else null) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = if (enabled) LocalCourierPalette.current.accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

/** Explicit manual share only. Never an automatic diagnostics upload. */
private fun exportBoltRecoveryTruth(context: android.content.Context) {
    val rows = runCatching { RouteResearchDatabase.get(context).boltRecoveryTruthRows(1000) }
        .getOrDefault(emptyList())
    if (rows.isEmpty()) return
    val directory = File(context.filesDir, "diagnostics/bolt-research").apply { mkdirs() }
    val json = JSONArray()
    rows.forEach { row ->
        json.put(JSONObject().apply {
            put("offer_id", row.offerId)
            put("recovered_lat", row.recovered.latitude)
            put("recovered_lon", row.recovered.longitude)
            put("truth_lat", row.truth.latitude)
            put("truth_lon", row.truth.longitude)
            put("error_m", row.errorMeters)
            put("scale_m_per_px", row.scaleMetersPerPixel)
            put("baseline_px", row.anchorBaselinePx)
            put("baseline_m", row.anchorBaselineMeters)
            put("pickup_markers", row.pickupMarkerCount)
            put("dropoff_markers", row.dropoffMarkerCount)
            put("eta_min", row.etaMinutes)
            put("route_meters", row.routeMeters)
            put("created_at", row.createdAt)
        })
    }
    val export = File(directory, "bolt-recovery-truth.json")
    runCatching { export.writeText(json.toString(2)) }.getOrElse { return }
    val files = listOf(export) + BoltRecoveryTruth.matchingResearchFiles(context, rows.map { it.offerId })
    val uris = ArrayList<Uri>(files.map {
        FileProvider.getUriForFile(context, "${context.packageName}.researchfiles", it)
    })
    val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(share, "Share private Bolt recovery research"))
}
