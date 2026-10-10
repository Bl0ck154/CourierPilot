package com.block154.courierpilot

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.block154.courierpilot.ui.GroupedRow
import com.block154.courierpilot.ui.IconTile
import com.block154.courierpilot.ui.LinkRow
import com.block154.courierpilot.ui.LocalCourierPalette
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

    Column {
        GroupedRow(index = 0, count = 2) {
            AppUpdateStatusPanel(
                status = status,
                initialLoading = initialLoading,
                busy = busy,
                idleLabel = "Check for updates now",
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
                        InstallLaunchResult.NOT_READY -> scope.launch {
                            status = withContext(Dispatchers.IO) { AppUpdateManager.snapshot(context) }
                        }
                    }
                } else {
                    AppUpdateManager.checkNow(context) { status = it }
                }
            }
        }
        LinkRow(
            index = 1,
            count = 2,
            icon = Icons.Rounded.Settings,
            title = "Automatic update settings",
            subtitle = "",
        ) { context.startActivity(Intent(context, AppUpdateActivity::class.java)) }
    }
}

/**
 * Version, status message, download progress and the single primary update action. Shared by the
 * Settings summary and the App updates screen so both read the same.
 */
@Composable
internal fun AppUpdateStatusPanel(
    status: AppUpdateStatus,
    initialLoading: Boolean,
    busy: Boolean,
    idleLabel: String,
    modifier: Modifier = Modifier,
    onPrimary: () -> Unit,
) {
    val palette = LocalCourierPalette.current
    val ready = status.phase == AppUpdatePhase.READY
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconTile(Icons.Rounded.SystemUpdate)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("CourierPilot ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    status.message,
                    color = if (status.phase == AppUpdatePhase.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                )
            }
        }

        if (status.phase == AppUpdatePhase.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { (status.progressPercent ?: 0).coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = palette.accent,
                trackColor = palette.line,
            )
        }

        Button(
            onClick = onPrimary,
            enabled = !busy,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Icon(
                if (ready) Icons.Rounded.SystemUpdate else Icons.Rounded.Download,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                when {
                    initialLoading -> "Loading…"
                    status.phase == AppUpdatePhase.CHECKING -> "Checking…"
                    status.phase == AppUpdatePhase.DOWNLOADING -> "Downloading ${status.progressPercent ?: 0}%"
                    ready -> "Install ${status.version ?: "update"}"
                    status.phase == AppUpdatePhase.AVAILABLE -> "Download ${status.version ?: "update"}"
                    else -> idleLabel
                },
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
    }
}
