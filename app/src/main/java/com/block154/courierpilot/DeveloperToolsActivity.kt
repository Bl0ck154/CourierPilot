package com.block154.courierpilot

import android.content.Intent
import android.net.Uri
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.FileProvider
import java.util.Locale
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.block154.courierpilot.ui.CourierPilotTheme

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
                DeveloperToolsScreen(onBack = ::finish)
            }
        }
    }
}

@Composable
private fun DeveloperToolsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val routeReady = runCatching { RouteEndpointSettings.load(context).validated() }.isSuccess
    val boltSample = BoltAccessibilityDiagnostics.summary(context)
    val truthDb = runCatching { RouteResearchDatabase.get(context) }.getOrNull()
    val truthStats = runCatching { truthDb?.boltRecoveryStats() }.getOrNull()
    val truthRows = runCatching { truthDb?.boltRecoveryTruthRows(20).orEmpty() }.getOrDefault(emptyList())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Developer tools", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    Text("Internal diagnostics and route validation", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.BugReport, contentDescription = null)
                        Spacer(Modifier.size(10.dp))
                        Text("Research-only", fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "These controls are for CourierPilot development. Normal users do not need server URLs, tokens, raw Accessibility trees or manual coordinates.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Route research", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(
                        if (routeReady) "Protected route service configured on this device" else "Route service is not provisioned on this device",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    FilledTonalButton(
                        onClick = { context.startActivity(Intent(context, RouteResearchActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Map, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Open manual route research")
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bolt research", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(
                        boltSample?.let {
                            "Last private sample: ${it.nodeCount} nodes · screenshot ${if (it.screenshotAvailable) "yes" else "no"} · GPS ${if (it.locationAvailable) "yes" else "no"}"
                        } ?: "No private Bolt research sample saved",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    FilledTonalButton(
                        onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Settings, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Open Accessibility services")
                    }
                }
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bolt recovery accuracy · local only", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    val count = truthStats?.count ?: 0
                    fun metres(value: Double?): String =
                        value?.let { String.format(Locale.US, "%.0f m", it) } ?: "—"
                    Text("$count matched deliveries · median ${metres(truthStats?.medianMeters)} · p80 ${metres(truthStats?.p80Meters)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    truthRows.forEach { item ->
                        Text("Offer #${item.offerId} · error ${metres(item.errorMeters)} · markers ${item.pickupMarkerCount}/${item.dropoffMarkerCount} · ETA ${item.etaMinutes ?: "?"} min",
                            fontSize = 11.sp)
                    }
                    Text("Export includes local coordinates and may include the saved research screenshot/tree. Share only deliberately.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(
                        onClick = { exportBoltRecoveryTruth(context) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = count > 0,
                    ) { Text("Export private Bolt research (share sheet)") }
                }
            }
        }

        item {
            TextButton(
                onClick = {
                    DeveloperModeSettings.setEnabled(context, false)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Disable developer mode")
            }
        }
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
    val files = listOf(export) + BoltAccessibilityDiagnostics.sampleFiles(context)
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
