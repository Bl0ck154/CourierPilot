package com.block154.courierpilot

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.block154.courierpilot.ui.ActionRow
import com.block154.courierpilot.ui.CourierPilotTheme
import com.block154.courierpilot.ui.CourierPilotToggleRow
import com.block154.courierpilot.ui.DetailHeader
import com.block154.courierpilot.ui.Footnote
import com.block154.courierpilot.ui.GroupedBlock
import com.block154.courierpilot.ui.GroupedRow
import com.block154.courierpilot.ui.LinkRow
import com.block154.courierpilot.ui.LocalCourierPalette
import com.block154.courierpilot.ui.SectionLabel
import com.block154.courierpilot.ui.SettingsDivider
import com.block154.courierpilot.ui.SettingsGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class ReliabilityActivity : ComponentActivity() {
    private val refresh = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val refreshToken = refresh.intValue
            CourierPilotTheme {
                // Surface supplies onBackground as the content colour for headers and plain text.
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ReliabilityScreen(
                        refreshToken = refreshToken,
                        onBack = ::finish,
                        onRefresh = { refresh.intValue++ },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh.intValue++
    }
}

private data class ReliabilityDiagnosticsSnapshot(
    val events: List<CaptureEvent>,
    val remote: RemoteDiagnosticsStatus,
)

@Composable
private fun ReliabilityScreen(refreshToken: Int, onBack: () -> Unit, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val notificationOk = reliabilityNotificationAccess(context)
    val accessibilityOk = reliabilityAccessibilityAccess(context)
    val power = context.getSystemService(PowerManager::class.java)
    val unrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) == true
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val backgroundRestricted = if (Build.VERSION.SDK_INT >= 28) activityManager?.isBackgroundRestricted == true else false
    val pending = OfferState.pending(context)
    val error = OfferState.lastError(context)
    var diagnostics by remember { mutableStateOf<ReliabilityDiagnosticsSnapshot?>(null) }
    var remoteEnabled by remember { mutableStateOf(RemoteDiagnostics.enabled(context)) }
    var manualDiagnosticsExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(refreshToken) {
        val loaded = withContext(Dispatchers.IO) {
            ReliabilityDiagnosticsSnapshot(
                events = CaptureEventLog.recent(context, 30),
                remote = RemoteDiagnostics.status(context),
            )
        }
        diagnostics = loaded
        remoteEnabled = loaded.remote.enabled
    }

    val events = diagnostics?.events.orEmpty()
    val remoteDiagnostics = diagnostics?.remote ?: RemoteDiagnosticsStatus(
        enabled = remoteEnabled,
        queued = 0,
        lastUploadAt = 0L,
        lastError = "",
    )

    LaunchedEffect(remoteEnabled) {
        if (remoteEnabled) {
            // Give the initial diagnostics_enabled heartbeat time to leave the local queue, then
            // refresh once so the user can see the first successful upload without reopening this screen.
            delay(6_000L)
            onRefresh()
        }
    }

    val palette = LocalCourierPalette.current
    val statusOk = palette.onlineText
    val statusBad = MaterialTheme.colorScheme.error
    val visibleEvents = events.take(10)
    val developerEnabled = DeveloperModeSettings.enabled(context)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
    ) {
        item { DetailHeader("Reliability", "Capture health and Android access", onBack) }

        item { SectionLabel("Required access") }
        item {
            LinkRow(
                index = 0,
                count = 2,
                icon = Icons.Rounded.NotificationsActive,
                title = "Notification access",
                subtitle = if (notificationOk) "Connected" else "Needed to detect incoming offers",
                subtitleColor = if (notificationOk) statusOk else statusBad,
            ) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        item {
            LinkRow(
                index = 1,
                count = 2,
                icon = Icons.Rounded.Shield,
                title = "Accessibility capture",
                subtitle = if (accessibilityOk) "Connected" else "Needed for screenshots and OCR",
                subtitleColor = if (accessibilityOk) statusOk else statusBad,
            ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        item { Footnote("Services used for automatic capture.") }

        item { SectionLabel("Background health") }
        item {
            LinkRow(
                index = 0,
                count = 2,
                icon = Icons.Rounded.BatteryChargingFull,
                title = "Battery optimization",
                subtitle = if (unrestricted) "Unrestricted" else "Set battery usage to Unrestricted / Don't optimize",
                subtitleColor = if (unrestricted) statusOk else statusBad,
            ) {
                runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    .onFailure { reliabilityOpenAppInfo(context) }
            }
        }
        item {
            LinkRow(
                index = 1,
                count = 2,
                icon = Icons.Rounded.PhoneAndroid,
                title = "Background restriction",
                subtitle = if (backgroundRestricted) "Android reports background activity as restricted" else "No restriction reported",
                subtitleColor = if (backgroundRestricted) statusBad else statusOk,
            ) { reliabilityOpenAppInfo(context) }
        }
        item { Footnote("Android restrictions that can interrupt capture.") }

        item { SectionLabel("Current capture") }
        item {
            GroupedBlock {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReliabilityMiniStat(
                            "Pending",
                            pending?.let { "${OfferState.platformLabel(it.packageName)} · ${reliabilityTime(it.armedAt)}" } ?: "None",
                            Modifier.weight(1f),
                        )
                        ReliabilityMiniStat(
                            "Screenshots",
                            if (CaptureStorageSettings.saveOfferScreenshots(context)) "Enabled" else "Off",
                            Modifier.weight(1f),
                        )
                    }
                    ReliabilityMiniStat(
                        "Last capture",
                        OfferState.lastCapture(context),
                        Modifier.fillMaxWidth(),
                        compactValue = true,
                    )
                    if (error.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Text(error, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.5.sp, lineHeight = 17.sp)
                            }
                        }
                    }
                }
            }
        }

        item { SectionLabel("Diagnostics") }
        item {
            SettingsGroup {
                CourierPilotToggleRow(
                    title = "Remote diagnostics",
                    subtitle = "Privacy-safe technical events only. No screenshots, addresses, customer text or GPS coordinates.",
                    checked = remoteEnabled,
                ) { enabled ->
                    // Update the visible control first; persistence result is then reconciled below.
                    remoteEnabled = enabled
                    val persisted = RemoteDiagnostics.setEnabled(context, enabled)
                    if (!persisted) {
                        remoteEnabled = RemoteDiagnostics.enabled(context)
                    } else if (enabled) {
                        // First end-to-end heartbeat: if this reaches the server, toggle + queue + HTTPS work.
                        CaptureEventLog.append(
                            context,
                            stage = "diagnostics_enabled",
                            message = "Remote diagnostics enabled",
                        )
                    }
                    onRefresh()
                }
                if (remoteEnabled) {
                    SettingsDivider()
                    Column(Modifier.padding(horizontal = 4.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Server logging", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            reliabilityRemoteStatus(remoteDiagnostics),
                            color = if (remoteDiagnostics.lastError.isBlank()) statusOk else statusBad,
                            fontSize = 12.5.sp,
                        )
                    }
                }
            }
        }
        item { Footnote("Automatic remote logs; manual export only when needed.") }

        item { SectionLabel("Manual diagnostics") }
        val manualCount = if (manualDiagnosticsExpanded) 1 + visibleEvents.size.coerceAtLeast(1) else 1
        item {
            LinkRow(
                index = 0,
                count = manualCount,
                icon = Icons.Rounded.BugReport,
                title = if (manualDiagnosticsExpanded) "Hide manual diagnostics" else "Show manual diagnostics",
                subtitle = "Event log and a shareable report",
                trailing = {
                    Icon(
                        if (manualDiagnosticsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ) { manualDiagnosticsExpanded = !manualDiagnosticsExpanded }
        }
        if (manualDiagnosticsExpanded) {
            if (visibleEvents.isEmpty()) {
                item {
                    GroupedRow(index = 1, count = manualCount) {
                        Text(
                            "No diagnostic events yet.",
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.5.sp,
                        )
                    }
                }
            } else {
                visibleEvents.forEachIndexed { index, event ->
                    item {
                        GroupedRow(index = index + 1, count = manualCount) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "${reliabilityTime(event.timestamp)} · ${event.stage}${event.platform.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(event.message, fontSize = 14.sp, lineHeight = 19.sp)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
            item { ActionRow(index = 0, count = 2, text = "Share diagnostics manually") { reliabilityShareDiagnostics(context) } }
            item {
                ActionRow(index = 1, count = 2, text = "Clear local event log", color = MaterialTheme.colorScheme.error) {
                    CaptureEventLog.clear(context)
                    onRefresh()
                }
            }
        }

        if (developerEnabled) {
            item { Spacer(Modifier.height(10.dp)) }
            item {
                LinkRow(
                    index = 0,
                    count = 1,
                    icon = Icons.Rounded.BugReport,
                    title = "Developer tools",
                    subtitle = "Route research and debug switches",
                ) { context.startActivity(Intent(context, DeveloperToolsActivity::class.java)) }
            }
        }

        item {
            Text(
                "CourierPilot ${reliabilityVersion(context)}",
                modifier = Modifier.fillMaxWidth().padding(top = 26.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Small metric tile on the soft stat background, like the dashboard's StatsMini. */
@Composable
private fun ReliabilityMiniStat(label: String, value: String, modifier: Modifier = Modifier, compactValue: Boolean = false) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = LocalCourierPalette.current.miniStatBg) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 1)
            if (compactValue) {
                // File names can be long: keep them readable instead of a clipped 18 sp line.
                Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 19.sp)
            } else {
                Text(value, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun reliabilityRemoteStatus(status: RemoteDiagnosticsStatus): String = when {
    status.lastError.isNotBlank() -> "Upload problem: ${status.lastError} · ${status.queued} queued"
    status.lastUploadAt > 0L -> "On · last upload ${reliabilityTime(status.lastUploadAt)} · ${status.queued} queued"
    else -> "On · waiting for first upload · ${status.queued} queued"
}

private fun reliabilityNotificationAccess(context: android.content.Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    return enabled.split(':').any { ComponentName.unflattenFromString(it)?.packageName == context.packageName }
}

private fun reliabilityAccessibilityAccess(context: android.content.Context): Boolean {
    if (Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
    val target = ComponentName(context, OfferAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == target }
}

private fun reliabilityOpenAppInfo(context: android.content.Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
}

private fun reliabilityShareDiagnostics(context: android.content.Context) {
    val power = context.getSystemService(PowerManager::class.java)
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val pending = OfferState.pending(context)
    val remote = RemoteDiagnostics.status(context)
    val body = buildString {
        appendLine("CourierPilot ${reliabilityVersion(context)}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Notification access: ${reliabilityNotificationAccess(context)}")
        appendLine("Accessibility: ${reliabilityAccessibilityAccess(context)}")
        appendLine("Ignoring battery optimizations: ${power?.isIgnoringBatteryOptimizations(context.packageName) == true}")
        if (Build.VERSION.SDK_INT >= 28) appendLine("Background restricted: ${activityManager?.isBackgroundRestricted == true}")
        appendLine("Gallery screenshots: ${CaptureStorageSettings.saveOfferScreenshots(context)}")
        appendLine("Remote diagnostics: ${remote.enabled}; queued=${remote.queued}; lastUpload=${remote.lastUploadAt}; error=${remote.lastError}")
        appendLine("Pending: ${pending?.let { OfferState.platformLabel(it.packageName) } ?: "none"}")
        appendLine("Last capture: ${OfferState.lastCapture(context)}")
        appendLine("Last error: ${OfferState.lastError(context)}")
        appendLine()
        appendLine("Event log (privacy-safe):")
        append(CaptureEventLog.asText(context))
    }
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "CourierPilot diagnostics")
        putExtra(Intent.EXTRA_TEXT, body)
    }, "Share CourierPilot diagnostics"))
}

private fun reliabilityVersion(context: android.content.Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}.getOrDefault("")

private fun reliabilityTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
