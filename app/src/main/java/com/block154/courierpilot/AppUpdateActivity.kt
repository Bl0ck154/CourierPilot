package com.block154.courierpilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.block154.courierpilot.ui.ActionRow
import com.block154.courierpilot.ui.CourierPilotTheme
import com.block154.courierpilot.ui.CourierPilotToggleRow
import com.block154.courierpilot.ui.DetailHeader
import com.block154.courierpilot.ui.Footnote
import com.block154.courierpilot.ui.GroupedBlock
import com.block154.courierpilot.ui.GroupedRow
import com.block154.courierpilot.ui.SectionLabel
import com.block154.courierpilot.ui.SettingsDivider
import com.block154.courierpilot.ui.SettingsGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppUpdateActivity : ComponentActivity() {
    private val refreshVersion = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val refresh = refreshVersion.intValue
            CourierPilotTheme {
                // Surface supplies onBackground as the content colour for headers and plain text.
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppUpdateScreen(refresh = refresh, onBack = ::finish)
                }
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
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(AppUpdateStatus(AppUpdatePhase.IDLE, message = "Loading update status…")) }
    var initialLoading by remember { mutableStateOf(true) }
    var autoDownload by remember { mutableStateOf(AppUpdateSettings.autoDownload(context)) }
    var wifiOnly by remember { mutableStateOf(AppUpdateSettings.wifiOnly(context)) }

    LaunchedEffect(refresh) {
        val snapshot = withContext(Dispatchers.IO) { AppUpdateManager.snapshot(context) }
        status = snapshot
        autoDownload = AppUpdateSettings.autoDownload(context)
        wifiOnly = AppUpdateSettings.wifiOnly(context)
        initialLoading = false
    }

    val busy = initialLoading || status.phase == AppUpdatePhase.CHECKING || status.phase == AppUpdatePhase.DOWNLOADING
    val ready = status.phase == AppUpdatePhase.READY
    // CourierPilotTheme already keeps content inside the safe-drawing insets.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
    ) {
        item { DetailHeader("App updates", "GitHub Releases · secure APK verification", onBack) }

        item { SectionLabel("This version") }
        val statusRows = if (ready) 2 else 1
        item {
            GroupedRow(index = 0, count = statusRows) {
                AppUpdateStatusPanel(
                    status = status,
                    initialLoading = initialLoading,
                    busy = busy,
                    idleLabel = "Check & download now",
                    modifier = Modifier.weight(1f).padding(vertical = 2.dp),
                ) {
                    if (ready) {
                        when (AppUpdateManager.requestInstall(context)) {
                            InstallLaunchResult.INSTALLER_OPENED -> Unit
                            InstallLaunchResult.PERMISSION_SETTINGS_OPENED -> {
                                status = status.copy(
                                    message = "Allow CourierPilot to install unknown apps, then return and tap Install again.",
                                )
                            }
                            InstallLaunchResult.NOT_READY -> {
                                scope.launch {
                                    status = withContext(Dispatchers.IO) { AppUpdateManager.snapshot(context) }
                                }
                            }
                        }
                    } else {
                        scope.launch(Dispatchers.IO) {
                            AppUpdateManager.checkNow(context) { status = it }
                        }
                    }
                }
            }
        }
        if (ready) {
            item {
                ActionRow(index = 1, count = statusRows, text = "Check again") {
                    if (!busy) {
                        scope.launch(Dispatchers.IO) {
                            AppUpdateManager.checkNow(context) { status = it }
                        }
                    }
                }
            }
        }

        item { SectionLabel("Automatic updates") }
        item {
            SettingsGroup {
                CourierPilotToggleRow(
                    title = "Automatically download updates",
                    subtitle = "When a newer release is found, download and verify that APK once in the background.",
                    checked = autoDownload,
                ) { enabled ->
                    autoDownload = enabled
                    AppUpdateSettings.setAutoDownload(context, enabled)
                }
                SettingsDivider()
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
        item {
            Footnote(
                "CourierPilot checks roughly every 30 minutes. Android may batch the background job, so it is not exact to the minute.",
            )
        }

        item { SectionLabel("How installs work") }
        item {
            GroupedBlock {
                Text(
                    "Background result: when an update is ready, Android shows a normal CourierPilot notification with Install and Later. Swiping it away or tapping Later hides that same version without deleting the verified APK; it remains installable from Settings. Before installation CourierPilot verifies SHA-256, package name, version code and the permanent signing certificate. Android still shows its own final install confirmation.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}
