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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.block154.courierpilot.ui.CourierPilotTheme
import com.block154.courierpilot.ui.Success
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                }
                Column(Modifier.weight(1f)) {
                    Text("Reliability", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    Text("Android access and capture health", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }

        item { ReliabilitySection("Required access", "These two services power offer detection") }
        item {
            ReliabilityStatusCard(
                title = "Notification access",
                subtitle = if (notificationOk) "Connected" else "Needed to detect incoming offer notifications",
                ok = notificationOk,
                icon = Icons.Rounded.NotificationsActive,
                onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            )
        }
        item {
            ReliabilityStatusCard(
                title = "Accessibility capture",
                subtitle = if (accessibilityOk) "Connected" else "Needed to read courier screens and run OCR fallback",
                ok = accessibilityOk,
                icon = Icons.Rounded.Shield,
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            )
        }

        item { ReliabilitySection("Background health", "Android can stop capture when battery restrictions are aggressive") }
        item {
            ReliabilityStatusCard(
                title = "Battery optimization",
                subtitle = if (unrestricted) "CourierPilot is excluded from Doze optimization" else "Set battery usage to Unrestricted / Don't optimize",
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
                subtitle = if (backgroundRestricted) "Android reports background activity as restricted" else "No Android background restriction reported",
                ok = !backgroundRestricted,
                icon = Icons.Rounded.PhoneAndroid,
                onClick = { reliabilityOpenAppInfo(context) },
            )
        }

        item { ReliabilitySection("Current capture", "Useful when an offer was missed") }
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    ReliabilityFact(
                        "Pending offer",
                        pending?.let { "${OfferState.platformLabel(it.packageName)} · armed ${reliabilityTime(it.armedAt)}" } ?: "None",
                    )
                    ReliabilityFact(
                        "Gallery screenshots",
                        if (CaptureStorageSettings.saveOfferScreenshots(context)) "Enabled" else "Off · OCR still works in memory",
                    )
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

        item { ReliabilitySection("Diagnostic log", "Privacy-safe capture events; no raw customer text") }
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (events.isEmpty()) {
                        Text("No diagnostic events yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    } else {
                        events.take(12).forEach { event ->
                            Column {
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
                        Icon(Icons.Rounded.BugReport, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Share diagnostics")
                    }
                    TextButton(
                        onClick = {
                            CaptureEventLog.clear(context)
                            onRefresh()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Clear event log")
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
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ReliabilitySection(title: String, subtitle: String) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
    Card(onClick = onClick, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (ok) Success.copy(alpha = 0.12f) else MaterialTheme.colorScheme.errorContainer,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                    tint = if (ok) Success else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun ReliabilityFact(label: String, value: String) {
    Column {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text(value, fontSize = 13.sp)
    }
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
    val body = buildString {
        appendLine("CourierPilot ${reliabilityVersion(context)}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Notification access: ${reliabilityNotificationAccess(context)}")
        appendLine("Accessibility: ${reliabilityAccessibilityAccess(context)}")
        appendLine("Ignoring battery optimizations: ${power?.isIgnoringBatteryOptimizations(context.packageName) == true}")
        if (Build.VERSION.SDK_INT >= 28) appendLine("Background restricted: ${activityManager?.isBackgroundRestricted == true}")
        appendLine("Gallery screenshots: ${CaptureStorageSettings.saveOfferScreenshots(context)}")
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
