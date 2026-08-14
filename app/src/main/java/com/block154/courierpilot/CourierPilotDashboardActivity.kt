package com.block154.courierpilot

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.block154.courierpilot.ui.BrandBlue
import com.block154.courierpilot.ui.BrandCyan
import com.block154.courierpilot.ui.CourierPilotTheme
import com.block154.courierpilot.ui.Ink
import com.block154.courierpilot.ui.InkElevated
import com.block154.courierpilot.ui.Purple
import com.block154.courierpilot.ui.Success
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CourierPilotDashboardActivity : ComponentActivity() {
    private val refreshVersion = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            refreshVersion.intValue
            CourierPilotTheme {
                DashboardRoot(
                    offers = OfferDatabase.get(this),
                    meta = CourierMetaDatabase.get(this),
                    notificationOk = hasNotificationAccess(),
                    accessibilityOk = hasAccessibilityAccess(),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshVersion.intValue++
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }

    private fun hasAccessibilityAccess(): Boolean {
        if (Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
        val target = ComponentName(this, OfferAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == target }
    }
}

private enum class DashboardScreen { HOME, HISTORY, CODES, STATS, SETTINGS }

@Composable
private fun DashboardRoot(
    offers: OfferDatabase,
    meta: CourierMetaDatabase,
    notificationOk: Boolean,
    accessibilityOk: Boolean,
) {
    var screen by remember { mutableStateOf(DashboardScreen.HOME) }
    val context = LocalContext.current
    BackHandler(enabled = screen != DashboardScreen.HOME) { screen = DashboardScreen.HOME }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (screen != DashboardScreen.SETTINGS) {
                NavigationBar {
                    listOf(
                        Triple(DashboardScreen.HOME, "Home", Icons.Rounded.Home),
                        Triple(DashboardScreen.HISTORY, "History", Icons.Rounded.History),
                        Triple(DashboardScreen.CODES, "Codes", Icons.Rounded.Key),
                        Triple(DashboardScreen.STATS, "Stats", Icons.Rounded.BarChart),
                    ).forEach { (target, label, icon) ->
                        NavigationBarItem(
                            selected = screen == target,
                            onClick = { screen = target },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        when (screen) {
            DashboardScreen.HOME -> DashboardHome(
                offers,
                meta,
                notificationOk,
                accessibilityOk,
                padding,
                onSettings = { screen = DashboardScreen.SETTINGS },
                onHistory = { screen = DashboardScreen.HISTORY },
                onCodes = { screen = DashboardScreen.CODES },
                onOpenOffer = { id ->
                    context.startActivity(Intent(context, OfferDetailsActivity::class.java).putExtra(OfferDetailsActivity.EXTRA_OFFER_ID, id))
                },
            )
            DashboardScreen.HISTORY -> DashboardHistory(offers, padding) { id ->
                context.startActivity(Intent(context, OfferDetailsActivity::class.java).putExtra(OfferDetailsActivity.EXTRA_OFFER_ID, id))
            }
            DashboardScreen.CODES -> DashboardCodes(meta, padding)
            DashboardScreen.STATS -> DashboardStats(offers, meta, padding)
            DashboardScreen.SETTINGS -> DashboardSettings(notificationOk, accessibilityOk, padding) {
                screen = DashboardScreen.HOME
            }
        }
    }
}

@Composable
private fun DashboardHome(
    offers: OfferDatabase,
    meta: CourierMetaDatabase,
    notificationOk: Boolean,
    accessibilityOk: Boolean,
    padding: PaddingValues,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onCodes: () -> Unit,
    onOpenOffer: (Long) -> Unit,
) {
    val context = LocalContext.current
    val presence = CourierPresence.all(context)
    val work = meta.workSummarySince(dashStartOfDay(0))
    val today = offers.summarySince(dashStartOfDay(0))
    val recent = offers.recent(4).map { it.withCurrentParsedStructure() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 20.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            AutoPresenceHero(
                healthy = notificationOk && accessibilityOk,
                presence = presence,
                workTime = dashDuration(work.totalMillis),
                active = work.active,
                onSettings = onSettings,
            )
        }

        if (!notificationOk || !accessibilityOk) {
            item {
                Card(
                    onClick = onSettings,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WarningAmber, contentDescription = null)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Capture needs attention", fontWeight = FontWeight.SemiBold)
                            Text("Open settings to restore the required Android access.", fontSize = 12.sp)
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                    }
                }
            }
        }

        item { DashboardSection("Today", SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashboardMetric("Offers", today.count.toString(), "priced captures", BrandBlue, Modifier.weight(1f), onHistory)
                DashboardMetric("Avg offer", dashAveragePrice(today), "today", BrandCyan, Modifier.weight(1f), onHistory)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashboardMetric("Work time", dashDuration(work.totalMillis), "auto-detected", Success, Modifier.weight(1f), {})
                DashboardMetric("Door codes", meta.accessCodeCount().toString(), "saved locally", Purple, Modifier.weight(1f), onCodes)
            }
        }

        item { DashboardSection("Recent offers", "Tap an offer for its route and screenshot") }
        if (recent.isEmpty()) {
            item { DashboardEmpty("No priced offers captured yet.") }
        } else {
            items(recent, key = { it.id }) { record -> DashboardOfferCard(record) { onOpenOffer(record.id) } }
            item {
                TextButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) {
                    Text("View full history")
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                }
            }
        }

        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp)) {
                    Text("How work time is detected", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Persistent Wolt/Bolt notifications can start an online session. The courier screen can confirm online/offline. If a notification is swiped away, CourierPilot marks the signal unknown instead of pretending you went offline.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoPresenceHero(
    healthy: Boolean,
    presence: List<PlatformPresence>,
    workTime: String,
    active: Boolean,
    onSettings: () -> Unit,
) {
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(Ink, InkElevated, Color(0xFF173D68))))
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("CourierPilot", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (healthy) "Capture ready · automatic work tracking" else "Android access needs attention", color = Color(0xFFB9C6D8), fontSize = 12.sp)
                    }
                    FilledTonalIconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presence.forEach { item -> PresencePill(item, Modifier.weight(1f)) }
                }

                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(if (active) "Online time today" else "Tracked time today", color = Color(0xFFB9C6D8), fontSize = 12.sp)
                        Text(workTime, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(if (active) "● LIVE" else "AUTO", color = if (active) Color(0xFF7EE2A8) else Color(0xFFB9C6D8), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun PresencePill(item: PlatformPresence, modifier: Modifier = Modifier) {
    val tint = when (item.state) {
        PresenceSignal.ONLINE -> Color(0xFF7EE2A8)
        PresenceSignal.OFFLINE -> Color(0xFFFF9A8F)
        PresenceSignal.UNKNOWN -> Color(0xFFFFD180)
    }
    Surface(modifier = modifier, color = Color.White.copy(alpha = 0.08f), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(item.platform, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(item.state.name.lowercase().replaceFirstChar(Char::uppercase), color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(item.source, color = Color(0xFF9FB0C6), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DashboardHistory(offers: OfferDatabase, padding: PaddingValues, onOpenOffer: (Long) -> Unit) {
    val records = offers.recent(200).map { it.withCurrentParsedStructure() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 12.dp, 16.dp, padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { DashboardSection("Offer history", "Latest 200 priced captures") }
        if (records.isEmpty()) item { DashboardEmpty("No offers yet.") }
        else items(records, key = { it.id }) { record -> DashboardOfferCard(record) { onOpenOffer(record.id) } }
    }
}

@Composable
private fun DashboardCodes(meta: CourierMetaDatabase, padding: PaddingValues) {
    var query by remember { mutableStateOf("") }
    val records = meta.searchAccessCodes(query, 200)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 12.dp, 16.dp, padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { DashboardSection("Building access", "Codes learned locally from delivery instructions") }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search address or code") },
                leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
            )
        }
        item {
            Text(
                "Only building address + access code are copied here. Customer names and raw instructions are not stored in this memory database.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        if (records.isEmpty()) item { DashboardEmpty(if (query.isBlank()) "No access codes learned yet." else "No matches.") }
        else items(records, key = { it.id }) { item ->
            Card(shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(item.code, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.displayAddress, fontWeight = FontWeight.SemiBold)
                        Text("${item.platform} · seen ${item.seenCount}× · ${dashShortDate(item.lastSeenAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardStats(offers: OfferDatabase, meta: CourierMetaDatabase, padding: PaddingValues) {
    val today = offers.summarySince(dashStartOfDay(0))
    val seven = offers.summarySince(dashStartOfDay(6))
    val thirty = offers.summarySince(dashStartOfDay(29))
    val workToday = meta.workSummarySince(dashStartOfDay(0))
    val workSeven = meta.workSummarySince(dashStartOfDay(6))
    val workThirty = meta.workSummarySince(dashStartOfDay(29))

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 12.dp, 16.dp, padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DashboardSection("Statistics", "Offers + automatically detected online time") }
        item { StatsPeriod("Today", today, workToday) }
        item { StatsPeriod("Last 7 days", seven, workSeven) }
        item { StatsPeriod("Last 30 days", thirty, workThirty) }
    }
}

@Composable
private fun StatsPeriod(label: String, summary: OfferSummary, work: AutomaticWorkSummary) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Row {
                Text("${summary.count} offers", Modifier.weight(1f))
                Text(dashAveragePrice(summary), fontWeight = FontWeight.Medium)
            }
            Row {
                Text("Avg €/km", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(dashPerKm(summary))
            }
            Row {
                Text("Online time", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(dashDuration(work.totalMillis))
            }
        }
    }
}

@Composable
private fun DashboardSettings(notificationOk: Boolean, accessibilityOk: Boolean, padding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    var autoOpen by remember { mutableStateOf(OfferState.autoOpen(context)) }
    var wakeScreen by remember { mutableStateOf(OfferState.wakeScreen(context)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 12.dp, 16.dp, padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    Text("Capture, automation and reliability", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onBack) { Text("Done") }
            }
        }
        item {
            SettingsStatusCard("Notification access", notificationOk, Icons.Rounded.NotificationsActive) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        item {
            SettingsStatusCard("Accessibility capture", accessibilityOk, Icons.Rounded.Shield) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp)) {
                    SettingsSwitchRow("Auto-open real offer notifications", "Strict classifier; unrelated Wolt/Bolt notifications stay untouched.", autoOpen) {
                        autoOpen = it
                        OfferState.setAutoOpen(context, it)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    SettingsSwitchRow("Wake screen for offers", "Briefly wakes a sleeping screen after a matched offer notification.", wakeScreen) {
                        wakeScreen = it
                        OfferState.setWakeScreen(context, it)
                    }
                }
            }
        }
        item {
            FilledTonalButton(
                onClick = { context.startActivity(Intent(context, ReliabilityActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Shield, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Open Reliability Center")
            }
        }
        item {
            Text(
                "Work sessions are automatic in 0.7.0. There is no Start/End shift control.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SettingsStatusCard(label: String, ok: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(if (ok) "Enabled" else "Needs setup", color = if (ok) Success else MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun SettingsSwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun DashboardMetric(label: String, value: String, subtitle: String, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Box(Modifier.size(9.dp).background(accent, RoundedCornerShape(50)))
            Spacer(Modifier.height(10.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        }
    }
}

@Composable
private fun DashboardOfferCard(record: OfferRecord, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = if (record.platform == "Wolt") Color(0xFFE6F7FD) else Color(0xFFEAF8EE)) {
                Icon(Icons.Rounded.Storefront, contentDescription = null, modifier = Modifier.padding(10.dp), tint = if (record.platform == "Wolt") BrandCyan else Success)
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(record.restaurant ?: record.platform, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${record.platform} · ${dashShortDate(record.capturedAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                record.distanceMeters?.let { Text("${"%.1f".format(it / 1000.0)} km", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
            }
            Text("€${"%.2f".format(record.priceCents / 100.0)}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

@Composable
private fun DashboardSection(title: String, subtitle: String) {
    Column {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun DashboardEmpty(text: String) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Text(text, Modifier.fillMaxWidth().padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun dashStartOfDay(daysBack: Int): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, -daysBack)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun dashAveragePrice(summary: OfferSummary): String = summary.averagePriceCents?.let { "€%.2f".format(it / 100.0) } ?: "—"
private fun dashPerKm(summary: OfferSummary): String = summary.averageEurPerKm?.let { "€%.2f/km".format(it) } ?: "—"
private fun dashDuration(ms: Long): String {
    val minutes = (ms / 60_000L).coerceAtLeast(0L)
    val hours = minutes / 60L
    val rest = minutes % 60L
    return if (hours > 0) "${hours}h ${rest}m" else "${rest}m"
}
private fun dashShortDate(timestamp: Long): String = SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(Date(timestamp))
