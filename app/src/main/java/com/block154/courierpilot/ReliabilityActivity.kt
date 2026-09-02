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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.block154.courierpilot.ui.CourierPilotTheme
import com.block154.courierpilot.ui.CourierPilotToggleRow
import com.block154.courierpilot.ui.Success
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

class ReliabilityActivity : ComponentActivity() {
    private val refresh = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            refresh.intValue
            CourierPilotTheme {
                ReliabilityScreen(
                    onBack = ::finish,
                    onRefresh = { refresh.intValue++ },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh.intValue++
    }
}

@Composable
private fun ReliabilityScreen(onBack: () -> Unit, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val notificationOk = reliabilityNotificationAccess(context)
    val accessibilityOk = reliabilityAccessibilityAccess(context)
    val power = context.getSystemService(PowerManager::class.java)
    val unrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) == true
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val backgroundRestricted = if (Build.VERSION.SDK_INT >= 28) activityManager?.isBackgroundRestricted == true else false
    val pending = OfferState.pending(context)
    val error = OfferState.lastError(context)
    val events = CaptureEventLog.recent(context, 30)
    val remoteDiagnostics = RemoteDiagnostics.status(context)
    var remoteEnabled by remember(remoteDiagnostics.enabled) { mutableStateOf(remoteDiagnostics.enabled) }
    var manualDiagnosticsExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(remoteEnabled) {
        if (remoteEnabled) {
            // Give the initial diagnostics_enabled heartbeat time to leave the local queue, then
            // refresh once so the user can see the first successful upload without reopening this screen.
            delay(6_000L)
            onRefresh()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                }
                Column(Modifier.weight(1f)) {
                    Text("Reliability", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    Text("Capture health and Android access", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }

        item { ReliabilitySection("Required access", "Services used for automatic capture") }
        item {
            ReliabilityStatusCard(
                title = "Notification access",
                subtitle = if (notificationOk) "Connected" else "Needed to detect incoming offers",
                ok = notificationOk,
                icon = Icons.Rounded.NotificationsActive,
                onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            )
        }
        item {
            ReliabilityStatusCard(
                title = "Accessibility capture",
                subtitle = if (accessibilityOk) "Connected" else "Needed for screenshots and OCR",
                ok = accessibilityOk,
                icon = Icons.Rounded.Shield,
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            )
        }

        item { ReliabilitySection("Background health", "Android restrictions that can interrupt capture") }
        item {
            ReliabilityStatusCard(
                title = "Battery optimization",
                subtitle = if (unrestricted) "Unrestricted" else "Set battery usage to Unrestricted / Don't optimize",
                ok = unrestricted,
                icon = Icons.Rounded.BatteryChargingFull,
                onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                        .onFailure { reliabilityOpenAppInfo(context) }
                },
            )
        }
        item {
            ReliabilityStatusCard(
                title = "Background restriction",
                subtitle = if (backgroundRestricted) "Android reports background activity as restricted" else "No restriction reported",
                ok = !backgroundRestricted,
                icon = Icons.Rounded.PhoneAndroid,
                onClick = { reliabilityOpenAppInfo(context) },
            )
        }

        item { ReliabilitySection("Current capture", "Latest capture state") }
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        ReliabilityFact(
                            "Pending",
                            pending?.let { "${OfferState.platformLabel(it.packageName)} · ${reliabilityTime(it.armedAt)}" } ?: "None",
                            Modifier.weight(1f),
                        )
                        ReliabilityFact(
                            "Screenshots",
                            if (CaptureStorageSettings.saveOfferScreenshots(context)) "Enabled" else "Off",
                            Modifier.weight(1f),
                        )
                    }
                    ReliabilityFact("Last capture", OfferState.lastCapture(context))
                    if (error.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.size(8.dp))
                                Text(error, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        item { ReliabilitySection("Diagnostics", "Automatic remote logs; manual export only when needed") }
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        Text(
                            reliabilityRemoteStatus(remoteDiagnostics),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            color = if (remoteDiagnostics.lastError.isBlank()) Success else MaterialTheme.colorScheme.error,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    HorizontalDivider(Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                    TextButton(
                        onClick = { manualDiagnosticsExpanded = !manualDiagnosticsExpanded },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.BugReport, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(if (manualDiagnosticsExpanded) "Hide manual diagnostics" else "Show manual diagnostics")
                    }

                    if (manualDiagnosticsExpanded) {
                        if (events.isEmpty()) {
                            Text(
                                "No diagnostic events yet.",
                                modifier = Modifier.padding(horizontal = 8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        } else {
                            events.take(10).forEach { event ->
                                Column(Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
                                    Text(
                                        "${reliabilityTime(event.timestamp)} · ${event.stage}${event.platform.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(event.message, fontSize = 12.sp)
                                }
                            }
                        }
                        FilledTonalButton(
                            onClick = { reliabilityShareDiagnostics(context) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Share diagnostics manually")
                        }
                        TextButton(
                            onClick = {
                                CaptureEventLog.clear(context)
                                onRefresh()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Clear local event log")
                        }
                    }
                }
            }
        }

        if (DeveloperModeSettings.enabled(context)) {
            item {
                FilledTonalButton(
                    onClick = { context.startActivity(Intent(context, DeveloperToolsActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.BugReport, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Developer tools")
                }
            }
        }

        item {
            Text(
                "CourierPilot ${reliabilityVersion(context)}",
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ReliabilitySection(title: String, subtitle: String) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
private fun ReliabilityStatusCard(
    title: String,
    subtitle: String,
    ok: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (ok) Success.copy(alpha = 0.12f) else MaterialTheme.colorScheme.errorContainer,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.padding(9.dp),
                    tint = if (ok) Success else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.size(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun ReliabilityFact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text(value, fontSize = 13.sp)
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
