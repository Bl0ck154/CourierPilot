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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.block154.courierpilot.ui.AmberTint
import com.block154.courierpilot.ui.BlueTint
import com.block154.courierpilot.ui.BrandBlue
import com.block154.courierpilot.ui.BrandCyan
import com.block154.courierpilot.ui.CourierPilotTheme
import com.block154.courierpilot.ui.CyanTint
import com.block154.courierpilot.ui.GreenTint
import com.block154.courierpilot.ui.Ink
import com.block154.courierpilot.ui.InkElevated
import com.block154.courierpilot.ui.OfferHeatmap
import com.block154.courierpilot.ui.Purple
import com.block154.courierpilot.ui.Success
import com.block154.courierpilot.ui.VioletTint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CourierPilotComposeActivity : ComponentActivity() {
    private val refreshVersion = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            refreshVersion.intValue
            CourierPilotTheme {
                CourierPilotRoot(
                    database = OfferDatabase.get(this),
                    notificationOk = hasNotificationAccess(),
                    accessibilityOk = hasAccessibilityAccess(),
                    onRefresh = { refreshVersion.intValue++ },
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

private enum class PilotScreen { HOME, HISTORY, STATS, SETTINGS }
private enum class HistoryFilter { ALL, WOLT, BOLT, SINGLE, STACKED }

@Composable
private fun CourierPilotRoot(
    database: OfferDatabase,
    notificationOk: Boolean,
    accessibilityOk: Boolean,
    onRefresh: () -> Unit,
) {
    var screen by remember { mutableStateOf(PilotScreen.HOME) }
    val context = LocalContext.current

    BackHandler(enabled = screen != PilotScreen.HOME) { screen = PilotScreen.HOME }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (screen != PilotScreen.SETTINGS) {
                PilotBottomBar(screen) { screen = it }
            }
        },
    ) { padding ->
        when (screen) {
            PilotScreen.HOME -> HomeScreen(
                database,
                notificationOk,
                accessibilityOk,
                padding,
                onNavigate = { screen = it },
                onOpenOffer = { id ->
                    context.startActivity(Intent(context, OfferDetailsActivity::class.java).putExtra(OfferDetailsActivity.EXTRA_OFFER_ID, id))
                },
                onRefresh = onRefresh,
            )
            PilotScreen.HISTORY -> HistoryScreen(
                database,
                padding,
                onOpenOffer = { id ->
                    context.startActivity(Intent(context, OfferDetailsActivity::class.java).putExtra(OfferDetailsActivity.EXTRA_OFFER_ID, id))
                },
            )
            PilotScreen.STATS -> StatsScreen(database, padding)
            PilotScreen.SETTINGS -> SettingsScreen(
                notificationOk,
                accessibilityOk,
                padding,
                onBack = { screen = PilotScreen.HOME },
            )
        }
    }
}

@Composable
private fun PilotBottomBar(screen: PilotScreen, select: (PilotScreen) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        listOf(
            Triple(PilotScreen.HOME, "Home", Icons.Rounded.Home),
            Triple(PilotScreen.HISTORY, "History", Icons.Rounded.History),
            Triple(PilotScreen.STATS, "Stats", Icons.Rounded.BarChart),
        ).forEach { (target, label, icon) ->
            NavigationBarItem(
                selected = screen == target,
                onClick = { select(target) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun HomeScreen(
    database: OfferDatabase,
    notificationOk: Boolean,
    accessibilityOk: Boolean,
    padding: PaddingValues,
    onNavigate: (PilotScreen) -> Unit,
    onOpenOffer: (Long) -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val today = database.summarySince(startOfDay(0))
    val wolt = database.summarySince(startOfDay(0), "Wolt")
    val bolt = database.summarySince(startOfDay(0), "Bolt")
    val recent = database.recent(4).map { it.withCurrentParsedStructure() }
    val shift = database.activeShift()
    val shiftToday = database.shiftSummarySince(startOfDay(0))
    var selectedDay by remember { mutableStateOf<String?>(null) }
    val dayStats = database.dailyStats(365)

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
            CommandHero(
                healthy = notificationOk && accessibilityOk,
                shiftActive = shift != null,
                tracked = formatDuration(shiftToday.totalMillis),
                onSettings = { onNavigate(PilotScreen.SETTINGS) },
                onShift = {
                    if (shift == null) database.startShift() else database.endActiveShift()
                    CaptureEventLog.append(
                        context,
                        "shift",
                        if (shift == null) "Manual work shift started" else "Manual work shift ended",
                    )
                    onRefresh()
                },
            )
        }

        if (!notificationOk || !accessibilityOk) {
            item { AttentionCard(notificationOk, accessibilityOk) { onNavigate(PilotScreen.SETTINGS) } }
        }

        item { SectionHeader("Today", SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Offers", today.count.toString(), "priced captures", BlueTint, BrandBlue, Modifier.weight(1f)) {
                    onNavigate(PilotScreen.HISTORY)
                }
                MetricCard("Avg offer", formatAveragePrice(today), "today", CyanTint, BrandCyan, Modifier.weight(1f)) {
                    onNavigate(PilotScreen.STATS)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Avg €/km", formatPerKm(today), "distance offers", GreenTint, Success, Modifier.weight(1f)) {
                    onNavigate(PilotScreen.STATS)
                }
                MetricCard("Wolt / Bolt", "${wolt.count} / ${bolt.count}", "platform split", VioletTint, Purple, Modifier.weight(1f)) {
                    onNavigate(PilotScreen.STATS)
                }
            }
        }

        item { SectionHeader("Recent offers", "Tap for route, customers and screenshot") }
        if (recent.isEmpty()) {
            item { EmptySurface("No priced offers captured yet.") }
        } else {
            items(recent, key = { it.id }) { record ->
                ComposeOfferCard(record) { onOpenOffer(record.id) }
            }
            item {
                TextButton(onClick = { onNavigate(PilotScreen.HISTORY) }, modifier = Modifier.fillMaxWidth()) {
                    Text("View full history")
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                }
            }
        }

        item { SectionHeader("Offer activity", "Recent 16 weeks · every square is tappable") }
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Column(Modifier.padding(16.dp)) {
                    OfferHeatmap(
                        days = dayStats,
                        weeks = 16,
                        selectedDay = selectedDay,
                        onSelected = { selectedDay = it?.day },
                    )
                    val selected = dayStats.firstOrNull { it.day == selectedDay }
                    Text(
                        selected?.let { "${it.day} · ${it.count} offers · W ${it.woltCount} / B ${it.boltCount}" }
                            ?: "Tap a day for its summary",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandHero(
    healthy: Boolean,
    shiftActive: Boolean,
    tracked: String,
    onSettings: () -> Unit,
    onShift: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(Ink, InkElevated, Color(0xFF16345A))))
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("CourierPilot", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(
                                onClick = {},
                                label = { Text(if (healthy) "Capture ready" else "Needs attention") },
                                leadingIcon = {
                                    Icon(
                                        if (healthy) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                                        contentDescription = null,
                                        modifier = Modifier.size(17.dp),
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (healthy) Success.copy(alpha = 0.20f) else Color(0xFFFFB74D).copy(alpha = 0.20f),
                                    labelColor = Color.White,
                                    leadingIconContentColor = if (healthy) Color(0xFF7EE2A8) else Color(0xFFFFD180),
                                ),
                                border = null,
                            )
                            Text("Wolt + Bolt journal", color = Color(0xFFB9C6D8), fontSize = 12.sp)
                        }
                    }
                    FilledTonalIconButton(onClick = onSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (shiftActive) "Shift in progress" else "Work time", color = Color(0xFFB9C6D8), fontSize = 12.sp)
                        Text(tracked, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    }
                    FilledTonalButton(onClick = onShift) {
                        Icon(if (shiftActive) Icons.Rounded.PauseCircle else Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text(if (shiftActive) "End shift" else "Start shift")
                    }
                }
            }
        }
    }
}

@Composable
private fun AttentionCard(notificationOk: Boolean, accessibilityOk: Boolean, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Capture needs attention", fontWeight = FontWeight.SemiBold)
                Text(
                    buildList {
                        if (!notificationOk) add("Notification access")
                        if (!accessibilityOk) add("Accessibility")
                    }.joinToString(" + ") + " disabled",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    subtitle: String,
    lightTint: Color,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background == Color(0xFF080D17)
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = if (dark) MaterialTheme.colorScheme.surface else lightTint),
    ) {
        Column(Modifier.padding(16.dp)) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(accent))
            Spacer(Modifier.height(12.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text(value, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ComposeOfferCard(record: OfferRecord, onClick: () -> Unit) {
    val merchant = record.merchantNames.firstOrNull() ?: record.restaurant ?: "Venue not detected"
    val stacked = (record.deliveryCount ?: 1) > 1
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (record.platform == "Wolt") BlueTint else GreenTint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Storefront,
                    contentDescription = null,
                    tint = if (record.platform == "Wolt") BrandBlue else Success,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(merchant, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("€${formatCents(record.priceCents)}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TinyPill(record.platform, if (record.platform == "Wolt") BrandBlue else Success)
                    if (stacked) TinyPill("${record.deliveryCount} deliveries", Purple)
                }
                record.customerNames.takeIf { it.isNotEmpty() }?.let {
                    Text("→ ${it.joinToString(" · ")}", color = Success, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
                }
                val meta = buildList {
                    record.distanceMeters?.let { add(String.format(Locale.US, "%.2f km", it / 1000.0)) }
                    eta(record)?.let(::add)
                    add(formatClock(record.capturedAt))
                }
                Text(meta.joinToString("  ·  "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun TinyPill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(999.dp), color = color.copy(alpha = 0.12f)) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun HistoryScreen(database: OfferDatabase, padding: PaddingValues, onOpenOffer: (Long) -> Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(HistoryFilter.ALL) }
    val all = database.recordsSince(0L, 5000).map { it.withCurrentParsedStructure() }
    val filtered = all.filter { record ->
        matchesSearch(record, query) && when (filter) {
            HistoryFilter.ALL -> true
            HistoryFilter.WOLT -> record.platform == "Wolt"
            HistoryFilter.BOLT -> record.platform == "Bolt"
            HistoryFilter.SINGLE -> (record.deliveryCount ?: 1) <= 1
            HistoryFilter.STACKED -> (record.deliveryCount ?: 1) > 1
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 14.dp,
            bottom = padding.calculateBottomPadding() + 20.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionHeader("History", "Search every captured field") }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text("Venue, address, customer, price…") },
            )
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                listOf(
                    HistoryFilter.ALL to "All",
                    HistoryFilter.WOLT to "Wolt",
                    HistoryFilter.BOLT to "Bolt",
                    HistoryFilter.SINGLE to "Single",
                    HistoryFilter.STACKED to "Stacked",
                ).forEach { (value, label) ->
                    FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(label) })
                }
            }
        }
        item { Text("${filtered.size} matching offers", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
        if (filtered.isEmpty()) {
            item { EmptySurface("No offers match this search.") }
        } else {
            items(filtered, key = { it.id }) { record -> ComposeOfferCard(record) { onOpenOffer(record.id) } }
        }
    }
}

@Composable
private fun StatsScreen(database: OfferDatabase, padding: PaddingValues) {
    var period by remember { mutableIntStateOf(30) }
    val since = when (period) {
        1 -> startOfDay(0)
        7 -> startOfDay(-6)
        30 -> startOfDay(-29)
        else -> 0L
    }
    val summary = database.summarySince(since)
    val records = database.recordsSince(since).map { it.withCurrentParsedStructure() }
    val wolt = records.count { it.platform == "Wolt" }
    val bolt = records.count { it.platform == "Bolt" }
    val stacked = records.count { (it.deliveryCount ?: 1) > 1 }
    val shifts = database.shiftSummarySince(since)
    var selectedDay by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 14.dp,
            bottom = padding.calculateBottomPadding() + 20.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SectionHeader("Statistics", "Patterns, platforms and tracked work time") }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                listOf(1 to "Today", 7 to "7d", 30 to "30d", 0 to "All").forEach { (days, label) ->
                    FilterChip(selected = period == days, onClick = { period = days }, label = { Text(label) })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Offers", summary.count.toString(), "captured", BlueTint, BrandBlue, Modifier.weight(1f)) {}
                MetricCard("Avg offer", formatAveragePrice(summary), "priced offers", CyanTint, BrandCyan, Modifier.weight(1f)) {}
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Avg €/km", formatPerKm(summary), "distance known", GreenTint, Success, Modifier.weight(1f)) {}
                MetricCard("Work time", formatDuration(shifts.totalMillis), "tracked shifts", AmberTint, Color(0xFFD97706), Modifier.weight(1f)) {}
            }
        }
        item { SectionHeader("Mix", "Platform and stacked delivery split") }
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SplitBar("Wolt", wolt, "Bolt", bolt, BrandBlue, Success)
                    SplitBar("Single", records.size - stacked, "Stacked", stacked, BrandCyan, Purple)
                }
            }
        }
        item { SectionHeader("Activity calendar", "Recent 26 weeks · scroll and tap") }
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp)) {
                    OfferHeatmap(database.dailyStats(365), 26, selectedDay, { selectedDay = it?.day })
                    val selected = database.dailyStats(365).firstOrNull { it.day == selectedDay }
                    Text(
                        selected?.let { "${it.day} · ${it.count} offers · avg ${it.averagePriceCents?.let { cents -> String.format(Locale.US, "€%.2f", cents / 100.0) } ?: "—"}" }
                            ?: "Tap a day for its summary",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SplitBar(leftLabel: String, left: Int, rightLabel: String, right: Int, leftColor: Color, rightColor: Color) {
    val total = (left + right).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text("$leftLabel $left", modifier = Modifier.weight(1f), fontSize = 12.sp)
            Text("$rightLabel $right", fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(999.dp))) {
            if (left > 0) Box(Modifier.weight(left.toFloat() / total).fillMaxSize().background(leftColor))
            if (right > 0) Box(Modifier.weight(right.toFloat() / total).fillMaxSize().background(rightColor))
        }
    }
}

@Composable
private fun SettingsScreen(
    notificationOk: Boolean,
    accessibilityOk: Boolean,
    padding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 14.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text("Capture behavior and reliability", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onBack) { Text("Done") }
            }
        }
        item {
            SettingsActionCard(
                icon = Icons.Rounded.Shield,
                title = "Reliability center",
                subtitle = "Battery, auto-open, wake screen, alive reminder and event log",
                accent = BrandBlue,
            ) { context.startActivity(Intent(context, ReliabilityActivity::class.java)) }
        }
        item {
            SettingsActionCard(
                icon = if (notificationOk) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                title = "Notification access",
                subtitle = if (notificationOk) "Connected" else "Required for incoming offer detection",
                accent = if (notificationOk) Success else MaterialTheme.colorScheme.error,
            ) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        item {
            SettingsActionCard(
                icon = if (accessibilityOk) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                title = "Accessibility capture",
                subtitle = if (accessibilityOk) "Connected" else "Required for prices and screenshots",
                accent = if (accessibilityOk) Success else MaterialTheme.colorScheme.error,
            ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        item {
            SettingsActionCard(
                icon = Icons.Rounded.NotificationsActive,
                title = "Background presence",
                subtitle = "CourierPilot stays quiet unless something needs attention or you enable periodic reminders",
                accent = BrandCyan,
            ) { context.startActivity(Intent(context, ReliabilityActivity::class.java)) }
        }
    }
}

@Composable
private fun SettingsActionCard(icon: ImageVector, title: String, subtitle: String, accent: Color, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = accent.copy(alpha = 0.12f)) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.padding(10.dp).size(24.dp))
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun EmptySurface(message: String) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(24.dp))
    }
}

private fun matchesSearch(record: OfferRecord, rawQuery: String): Boolean {
    val query = rawQuery.trim().lowercase(Locale.ROOT)
    if (query.isBlank()) return true
    val price = String.format(Locale.US, "%.2f", record.priceCents / 100.0)
    val searchable = buildString {
        appendLine(record.platform)
        appendLine(record.restaurant.orEmpty())
        appendLine(record.merchantNames.joinToString(" "))
        appendLine(record.pickupAddresses.joinToString(" "))
        appendLine(record.customerNames.joinToString(" "))
        appendLine(record.dropoffAddresses.joinToString(" "))
        appendLine(record.rawText)
        appendLine("€$price ${price.replace('.', ',')}")
        record.distanceMeters?.let { appendLine(String.format(Locale.US, "%.2f km", it / 1000.0)) }
        appendLine(record.deliveryCount?.toString().orEmpty())
        appendLine(SimpleDateFormat("yyyy-MM-dd EEEE d MMMM HH:mm", Locale.getDefault()).format(Date(record.capturedAt)))
    }.lowercase(Locale.ROOT)
    return query.split(Regex("\\s+")).filter(String::isNotBlank).all { it in searchable }
}

private fun startOfDay(offset: Int): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, offset)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun formatAveragePrice(summary: OfferSummary): String =
    summary.averagePriceCents?.let { String.format(Locale.US, "€%.2f", it / 100.0) } ?: "—"

private fun formatPerKm(summary: OfferSummary): String =
    summary.averageEurPerKm?.let { String.format(Locale.US, "€%.2f", it) } ?: "—"

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0m"
    val minutes = ms / 60_000L
    val hours = minutes / 60
    val rest = minutes % 60
    return if (hours > 0) "${hours}h ${rest}m" else "${rest}m"
}

private fun formatCents(cents: Int): String = String.format(Locale.US, "%.2f", cents / 100.0)
private fun formatClock(timestamp: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
private fun eta(record: OfferRecord): String? = when {
    record.estimatedMinutesMin != null && record.estimatedMinutesMax != null -> "${record.estimatedMinutesMin}–${record.estimatedMinutesMax} min"
    record.estimatedMinutesMin != null -> "${record.estimatedMinutesMin} min"
    else -> null
}
