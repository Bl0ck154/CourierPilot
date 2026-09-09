package com.block154.courierpilot

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AppUpdateSettingsSummaryCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember {
        mutableStateOf(AppUpdateStatus(AppUpdatePhase.IDLE, message = "Loading update status…"))
    }
    var initialLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        status = withContext(Dispatchers.IO) { AppUpdateManager.snapshot(context) }
        initialLoading = false
    }

    val busy = initialLoading || status.phase == AppUpdatePhase.CHECKING || status.phase == AppUpdatePhase.DOWNLOADING
    val ready = status.phase == AppUpdatePhase.READY

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
                LinearProgressIndicator(
                    progress = (status.progressPercent ?: 0).coerceIn(0, 100) / 100f,
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
                            InstallLaunchResult.NOT_READY -> scope.launch {
                                status = withContext(Dispatchers.IO) { AppUpdateManager.snapshot(context) }
                            }
                        }
                    } else {
                        AppUpdateManager.checkNow(context) { status = it }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(if (ready) Icons.Rounded.SystemUpdate else Icons.Rounded.Download, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    when {
                        initialLoading -> "Loading…"
                        status.phase == AppUpdatePhase.CHECKING -> "Checking…"
                        status.phase == AppUpdatePhase.DOWNLOADING -> "Downloading ${status.progressPercent ?: 0}%"
                        ready -> "Install ${status.version ?: "update"}"
                        status.phase == AppUpdatePhase.AVAILABLE -> "Download ${status.version ?: "update"}"
                        else -> "Check for updates now"
                    }
                )
            }

            TextButton(
                onClick = { context.startActivity(Intent(context, AppUpdateActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Automatic update settings")
            }
        }
    }
}
