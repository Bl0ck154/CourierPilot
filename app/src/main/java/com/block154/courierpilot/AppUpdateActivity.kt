package com.block154.courierpilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.block154.courierpilot.ui.CourierPilotTheme
import com.block154.courierpilot.ui.CourierPilotToggleRow

class AppUpdateActivity : ComponentActivity() {
    private val refreshVersion = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val refresh = refreshVersion.intValue
            CourierPilotTheme {
                AppUpdateScreen(refresh = refresh, onBack = ::finish)
            }
        }
        if (intent.getBooleanExtra(EXTRA_INSTALL_NOW, false)) {
            window.decorView.post {
                AppUpdateManager.requestInstall(this)
                intent.removeExtra(EXTRA_INSTALL_NOW)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshVersion.intValue++
    }

    companion object {
        const val EXTRA_INSTALL_NOW = "install_update_now"
    }
}

@Composable
private fun AppUpdateScreen(refresh: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(AppUpdateManager.snapshot(context)) }
    var autoDownload by remember { mutableStateOf(AppUpdateSettings.autoDownload(context)) }
    var wifiOnly by remember { mutableStateOf(AppUpdateSettings.wifiOnly(context)) }
    var frequency by remember { mutableStateOf(AppUpdateSettings.checkFrequency(context)) }

    LaunchedEffect(refresh) {
        status = AppUpdateManager.snapshot(context)
        autoDownload = AppUpdateSettings.autoDownload(context)
        wifiOnly = AppUpdateSettings.wifiOnly(context)
        frequency = AppUpdateSettings.checkFrequency(context)
    }

    val busy = status.phase == AppUpdatePhase.CHECKING || status.phase == AppUpdatePhase.DOWNLOADING
    val ready = status.phase == AppUpdatePhase.READY

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 10.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    Column(Modifier.weight(1f)) {
                        Text("App updates", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "GitHub Releases · secure APK verification",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            item {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.SystemUpdate, contentDescription = null)
                            Spacer(Modifier.size(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("CourierPilot ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.SemiBold)
                                Text(
                                    status.message,
                                    color = if (status.phase == AppUpdatePhase.ERROR) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontSize = 12.sp,
                                )
                            }
                        }

                        if (status.phase == AppUpdatePhase.DOWNLOADING) {
                            val progress = (status.progressPercent ?: 0).coerceIn(0, 100)
                            LinearProgressIndicator(
                                progress = progress / 100f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        FilledTonalButton(
                            onClick = {
                                if (ready) {
                                    when (AppUpdateManager.requestInstall(context)) {
                                        InstallLaunchResult.INSTALLER_OPENED -> Unit
                                        InstallLaunchResult.PERMISSION_SETTINGS_OPENED -> {
                                            status = status.copy(
                                                message = "Allow CourierPilot to install unknown apps, then return and tap Install again.",
                                            )
                                        }
                                        InstallLaunchResult.NOT_READY -> {
                                            status = AppUpdateManager.snapshot(context)
                                        }
                                    }
                                } else {
                                    AppUpdateManager.checkNow(context) { status = it }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                if (ready) Icons.Rounded.SystemUpdate else Icons.Rounded.Download,
                                contentDescription = null,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                when {
                                    status.phase == AppUpdatePhase.CHECKING -> "Checking…"
                                    status.phase == AppUpdatePhase.DOWNLOADING ->
                                        "Downloading ${status.progressPercent ?: 0}%"
                                    ready -> "Install ${status.version ?: "update"}"
                                    status.phase == AppUpdatePhase.AVAILABLE -> "Download ${status.version ?: "update"}"
                                    else -> "Check & download now"
                                }
                            )
                        }

                        if (ready) {
                            TextButton(
                                onClick = { AppUpdateManager.checkNow(context) { status = it } },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Check again")
                            }
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Automatic updates", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Android schedules these approximately, not to the exact minute. The minimum periodic interval is 15 minutes.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            }

            item {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Check frequency", fontWeight = FontWeight.SemiBold)
                        Text(
                            "1 hour is the recommended default. Shorter intervals only make a small GitHub release-metadata request when no update exists.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                        AppUpdateCheckFrequency.entries.forEach { option ->
                            TextButton(
                                onClick = {
                                    frequency = option
                                    AppUpdateSettings.setCheckFrequency(context, option)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                RadioButton(selected = frequency == option, onClick = null)
                                Spacer(Modifier.size(8.dp))
                                Text(option.label, modifier = Modifier.weight(1f))
                            }
                        }

                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        CourierPilotToggleRow(
                            title = "Automatically download updates",
                            subtitle = "When a newer release is found, download and verify that APK once in the background.",
                            checked = autoDownload,
                        ) { enabled ->
                            autoDownload = enabled
                            AppUpdateSettings.setAutoDownload(context, enabled)
                        }
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        CourierPilotToggleRow(
                            title = "Wi-Fi only for automatic downloads",
                            subtitle = "Manual Check & download now always uses the current connection.",
                            checked = wifiOnly,
                            enabled = autoDownload,
                        ) { enabled ->
                            wifiOnly = enabled
                            AppUpdateSettings.setWifiOnly(context, enabled)
                        }
                    }
                }
            }

            item {
                Text(
                    "Background result: when an update is ready, Android shows a normal CourierPilot notification with Install and Later. Swiping it away or tapping Later hides that same version without deleting the verified APK; it remains installable from Settings. Before installation CourierPilot verifies SHA-256, package name, version code and the permanent signing certificate. Android still shows its own final install confirmation.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
