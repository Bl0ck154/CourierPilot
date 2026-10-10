package com.block154.courierpilot

import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.block154.courierpilot.ui.ActionRow
import com.block154.courierpilot.ui.CourierPilotTheme
import com.block154.courierpilot.ui.CourierPilotToggleRow
import com.block154.courierpilot.ui.DetailHeader
import com.block154.courierpilot.ui.FilterChipD
import com.block154.courierpilot.ui.Footnote
import com.block154.courierpilot.ui.GroupedBlock
import com.block154.courierpilot.ui.LocalCourierPalette
import com.block154.courierpilot.ui.SectionLabel
import com.block154.courierpilot.ui.SettingsGroup
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Small storage screen kept independent from the main dashboard so maintenance stays low-risk. */
class StorageSettingsActivity : ComponentActivity() {
    private var saveScreenshots by mutableStateOf(true)
    private var retentionDays by mutableIntStateOf(CaptureStorageSettings.DEFAULT_RETENTION_DAYS)
    private var stats by mutableStateOf<StorageStatsText?>(null)
    private var scanning by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "CourierPilot storage"
        enableEdgeToEdge()
        saveScreenshots = CaptureStorageSettings.saveOfferScreenshots(this)
        setContent {
            CourierPilotTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    StorageScreen(
                        saveScreenshots = saveScreenshots,
                        retentionDays = retentionDays,
                        stats = stats,
                        scanning = scanning,
                        onSaveScreenshotsChange = { checked ->
                            saveScreenshots = checked
                            CaptureStorageSettings.setSaveOfferScreenshots(this, checked)
                            refreshStats()
                        },
                        onRetentionSelected = { days ->
                            CaptureStorageSettings.setRetentionDays(this, days)
                            refreshStats()
                            runCleanup(force = true)
                        },
                        onCleanNow = { runCleanup(force = true) },
                        onBack = ::finish,
                    )
                }
            }
        }
        refreshStats()
    }

    override fun onResume() {
        super.onResume()
        saveScreenshots = CaptureStorageSettings.saveOfferScreenshots(this)
        refreshStats()
    }

    private fun runCleanup(force: Boolean) {
        scanning = true
        Thread({
            val result = ScreenshotRetentionManager.runIfDue(this, force)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (result.deletedCount > 0) {
                        "Deleted ${result.deletedCount} old screenshots (${formatBytes(result.deletedBytes)})"
                    } else {
                        "Nothing old enough to delete"
                    },
                    Toast.LENGTH_SHORT,
                ).show()
                refreshStats()
            }
        }, "CourierPilot-storage-cleanup").apply { isDaemon = true }.start()
    }

    private fun refreshStats() {
        retentionDays = CaptureStorageSettings.retentionDays(this)
        Thread({
            val stats = ScreenshotRetentionManager.stats(this)
            val oldest = stats.oldestAt.takeIf { it > 0L }?.let {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
            } ?: "—"
            runOnUiThread {
                this.stats = StorageStatsText(
                    count = stats.count.toString(),
                    bytes = formatBytes(stats.bytes),
                    oldest = oldest,
                )
                scanning = false
            }
        }, "CourierPilot-storage-stats").apply { isDaemon = true }.start()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

private data class StorageStatsText(val count: String, val bytes: String, val oldest: String)

private const val SCREENSHOT_FOLDER = "Pictures/CourierOffers"

@Composable
private fun StorageScreen(
    saveScreenshots: Boolean,
    retentionDays: Int,
    stats: StorageStatsText?,
    scanning: Boolean,
    onSaveScreenshotsChange: (Boolean) -> Unit,
    onRetentionSelected: (Int) -> Unit,
    onCleanNow: () -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalCourierPalette.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
    ) {
        item { DetailHeader("Storage", "Offer screenshot storage", onBack) }

        item { SectionLabel("Screenshots") }
        item {
            SettingsGroup {
                CourierPilotToggleRow(
                    title = "Save offer screenshots",
                    subtitle = "Save PNG copies in $SCREENSHOT_FOLDER. OCR still works when gallery screenshots are disabled.",
                    checked = saveScreenshots,
                    onCheckedChange = onSaveScreenshotsChange,
                )
            }
        }

        item { SectionLabel("Retention") }
        item {
            SettingsGroup {
                Column(
                    Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "Retention: ${if (retentionDays == 0) "Forever" else "$retentionDays days"}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                    Text(
                        "Choosing a period cleans older screenshots right away.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(7, 30, 90, CaptureStorageSettings.RETENTION_FOREVER).forEach { days ->
                            FilterChipD(
                                label = if (days == 0) "Forever" else "${days}d",
                                selected = retentionDays == days,
                                onClick = { onRetentionSelected(days) },
                            )
                        }
                    }
                }
            }
        }
        item {
            Footnote("Retention only removes old PNG files from $SCREENSHOT_FOLDER; offer history and parsed data stay intact.")
        }

        item { SectionLabel("Usage") }
        item {
            GroupedBlock {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StorageMetric("Screenshots", if (scanning) "…" else stats?.count ?: "—", Modifier.weight(1f), palette.miniStatBg)
                        StorageMetric("Storage", if (scanning) "…" else stats?.bytes ?: "—", Modifier.weight(1f), palette.miniStatBg)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (scanning) "Scanning screenshots…" else "Oldest: ${stats?.oldest ?: "—"}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                        Text(
                            "Folder: $SCREENSHOT_FOLDER",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
        item { ActionRow(index = 0, count = 1, text = "Clean now", onClick = onCleanNow) }
    }
}

@Composable
private fun StorageMetric(label: String, value: String, modifier: Modifier, background: Color) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = background) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 1)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
    }
}
