package com.block154.courierpilot

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.material3.IconButton
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
import kotlin.math.ceil

class CourierPilotDashboardActivity : ComponentActivity() {
    private val refreshVersion = mutableIntStateOf(0)
    private val midnightHandler = Handler(Looper.getMainLooper())
    private val midnightRefresh = object : Runnable {
        override fun run() {
            refreshVersion.intValue++
            scheduleMidnightRefresh()
        }
    }

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
        scheduleStartupMaintenanceAfterFirstFrame()
    }


    private fun scheduleStartupMaintenanceAfterFirstFrame() {
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) return
        val appContext = applicationContext
        // Give Compose a real first frame before touching hundreds of historical rows. This also
        // means a cold process started only by a courier notification never pays the repair cost.
        window.decorView.postDelayed({
            Thread({
                runCatching {
                    AddressDataRepair.runIfNeeded(appContext)
                    OfferDataRepair.runIfNeeded(appContext)
                    AddressBackfill.schedule(appContext)
                }.onFailure { error ->
                    CaptureEventLog.append(
                        appContext,
                        stage = "startup_maintenance_failed",
                        message = error.javaClass.simpleName,
                        dedupeWindowMs = 60_000L,
                    )
                }
                runOnUiThread { refreshVersion.intValue++ }
            }, "CourierPilot-startup-maintenance").start()
        }, 700L)
    }

    override fun onResume() {
        super.onResume()
        refreshVersion.intValue++
        scheduleMidnightRefresh()
    }

    override fun onPause() {
        midnightHandler.removeCallbacks(midnightRefresh)
        super.onPause()
    }

    private fun scheduleMidnightRefresh() {
        midnightHandler.removeCallbacks(midnightRefresh)
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 250)
        }
        midnightHandler.postDelayed(midnightRefresh, (next.timeInMillis - now.timeInMillis).coerceAtLeast(1_000L))
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

private enum class DashboardScreen { HOME, HISTORY, ADDRESSES, STATS, SETTINGS }

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
                        Triple(DashboardScreen.ADDRESSES, "Addresses", Icons.Rounded.Place),
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
                offers = offers,
                meta = meta,
                notificationOk = notificationOk,
                accessibilityOk = accessibilityOk,
                padding = padding,
                onSettings = { screen = DashboardScreen.SETTINGS },
                onHistory = { screen = DashboardScreen.HISTORY },
                onStats = { screen = DashboardScreen.STATS },
                onOpenOffer = { id ->
                    context.startActivity(
                        Intent(context, OfferDetailsActivity::class.java)
                            .putExtra(OfferDetailsActivity.EXTRA_OFFER_ID, id)
                    )
                },
            )
            DashboardScreen.HISTORY -> DashboardHistory(offers, padding) { id ->
                context.startActivity(
                    Intent(context, OfferDetailsActivity::class.java)
                        .putExtra(OfferDetailsActivity.EXTRA_OFFER_ID, id)
                )
            }
            DashboardScreen.ADDRESSES -> DashboardAddresses(meta, padding) { id ->
                context.startActivity(
                    Intent(context, AddressDetailsActivity::class.java)
                        .putExtra(AddressDetailsActivity.EXTRA_ADDRESS_ID, id)
                )
            }
            DashboardScreen.STATS -> DashboardStats(
                offers = offers,
                meta = meta,
                padding = padding,
                onHistory = { screen = DashboardScreen.HISTORY },
                onAddresses = { screen = DashboardScreen.ADDRESSES },
            )
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
    onStats: () -> Unit,
    onOpenOffer: (Long) -> Unit,
) {
    val context = LocalContext.current
    val presence = CourierPresence.all(context)
    val work = meta.workSummarySince(dashStartOfDay(0))
    val today = offers.summarySince(dashStartOfDay(0))
    val recent = offers.recent(4).map { it.withCurrentParsedStructure() }
    val offersPerHour = dashOffersPerHour(today.count, work.totalMillis)

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
                            Text("Open settings to restore Android access.", fontSize = 12.sp)
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                    }
                }
            }
        }

        item { DashboardSection("Today", SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashboardMetric("Offers", today.count.toString(), "captured", BrandBlue, Modifier.weight(1f), onHistory)
                DashboardMetric("Avg offer", dashAveragePrice(today), "today", BrandCyan, Modifier.weight(1f), onStats)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashboardMetric("Work time", dashDuration(work.totalMillis), "auto-detected", Success, Modifier.weight(1f), onStats)
                DashboardMetric("Offers / hour", offersPerHour, "during tracked time", Purple, Modifier.weight(1f), onStats)
            }
        }

        item { DashboardSection("Recent offers", "Tap an offer to open all details") }
        if (recent.isEmpty()) {
            item { DashboardEmpty("No priced offers captured yet.") }
        } else {
            items(recent, key = { it.id }) { record ->
                DashboardOfferCard(record) { onOpenOffer(record.id) }
            }
            item {
                TextButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) {
                    Text("View full history")
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null)
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
                        Text(
                            if (healthy) "Automatic offer & work tracking" else "Android access needs attention",
                            color = Color(0xFFB9C6D8),
                            fontSize = 12.sp,
                        )
                    }
                    FilledTonalIconButton(onClick = onSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presence.forEach { item -> PresencePill(item, Modifier.weight(1f)) }
                }

                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("Online time today", color = Color(0xFFB9C6D8), fontSize = 12.sp)
                        Text(workTime, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (active) {
                        Text("● LIVE", color = Color(0xFF7EE2A8), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
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
        PresenceSignal.UNKNOWN -> Color(0xFFB9C6D8)
    }
    val status = when (item.state) {
        PresenceSignal.ONLINE -> "Online"
        PresenceSignal.OFFLINE -> "Offline"
        PresenceSignal.UNKNOWN -> "No signal"
    }
    Surface(modifier = modifier, color = Color.White.copy(alpha = 0.08f), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
            Text(item.platform, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(status, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun DashboardHistory(
    offers: OfferDatabase,
    padding: PaddingValues,
    onOpenOffer: (Long) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    val total = offers.offerCount(query)
    val pageCount = maxOf(1, ceil(total / HISTORY_PAGE_SIZE.toDouble()).toInt())
    if (page >= pageCount) page = pageCount - 1
    val records = offers.searchPage(query, HISTORY_PAGE_SIZE, page * HISTORY_PAGE_SIZE)
        .map { it.withCurrentParsedStructure() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 12.dp, 16.dp, padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { DashboardSection("Offer history", "$total captured offers") }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    page = 0
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search offers") },
                placeholder = { Text("Venue, address, customer, platform…") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            )
        }
        if (records.isEmpty()) {
            item { DashboardEmpty(if (query.isBlank()) "No offers yet." else "No offers match this search.") }
        } else {
            items(records, key = { it.id }) { record ->
                DashboardOfferCard(record) { onOpenOffer(record.id) }
            }
        }
        if (total > HISTORY_PAGE_SIZE) {
            item {
                PaginationRow(
                    page = page,
                    pageCount = pageCount,
                    onPrevious = { if (page > 0) page-- },
                    onNext = { if (page + 1 < pageCount) page++ },
                )
            }
        }
    }
}

@Composable
private fun DashboardAddresses(
    meta: CourierMetaDatabase,
    padding: PaddingValues,
    onOpenAddress: (Long) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val total = meta.addressCount(query)
    val pageCount = maxOf(1, ceil(total / ADDRESS_PAGE_SIZE.toDouble()).toInt())
    if (page >= pageCount) page = pageCount - 1
    val records = meta.searchAddresses(query, ADDRESS_PAGE_SIZE, page * ADDRESS_PAGE_SIZE)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 12.dp, 16.dp, padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { DashboardSection("Addresses", "$total buildings saved locally") }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    page = 0
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search addresses") },
                placeholder = { Text("Street, customer, code…") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            )
        }
        if (records.isEmpty()) {
            item { DashboardEmpty(if (query.isBlank()) "No addresses captured yet." else "No addresses match this search.") }
        } else {
            items(records, key = { it.id }) { address ->
                val codes = meta.codesForBuilding(address.buildingKey, 3).map { it.code }.distinct()
                Card(onClick = { onOpenAddress(address.id) }, shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(Icons.Rounded.Place, contentDescription = null, modifier = Modifier.padding(10.dp))
                        }
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(address.displayAddress, fontWeight = FontWeight.SemiBold)
                            val customer = address.latestCustomerName?.takeIf(String::isNotBlank)
                            if (customer != null) {
                                Text(customer, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                            Text(
                                buildString {
                                    append(address.platform)
                                    append(" · seen ${address.seenCount}×")
                                    if (codes.isNotEmpty()) append(" · ${codes.joinToString(" / ")}")
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { context.openAddressInMaps(address.displayAddress) }) {
                            Icon(Icons.Rounded.Map, contentDescription = "Open in maps")
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                    }
                }
            }
        }
        if (total > ADDRESS_PAGE_SIZE) {
            item {
                PaginationRow(
                    page = page,
                    pageCount = pageCount,
                    onPrevious = { if (page > 0) page-- },
                    onNext = { if (page + 1 < pageCount) page++ },
                )
            }
        }
    }
}

@Composable
private fun DashboardStats(
    offers: OfferDatabase,
    meta: CourierMetaDatabase,
    padding: PaddingValues,
    onHistory: () -> Unit,
    onAddresses: () -> Unit,
) {
    val today = offers.summarySince(dashStartOfDay(0))
    val seven = offers.summarySince(dashStartOfDay(6))
    val thirty = offers.summarySince(dashStartOfDay(29))
    val workToday = meta.workSummarySince(dashStartOfDay(0))
    val workSeven = meta.workSummarySince(dashStartOfDay(6))
    val workThirty = meta.workSummarySince(dashStartOfDay(29))
    val wolt = offers.summarySince(dashStartOfDay(29), "Wolt")
    val bolt = offers.summarySince(dashStartOfDay(29), "Bolt")
    val days = offers.dailyStats(14)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 12.dp, 16.dp, padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DashboardSection("Statistics", "Tap any period to open offer history") }
        item { StatsPeriod("Today", today, workToday, onHistory) }
        item { StatsPeriod("Last 7 days", seven, workSeven, onHistory) }
        item { StatsPeriod("Last 30 days", thirty, workThirty, onHistory) }

        item { DashboardSection("Platforms", "Last 30 days") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashboardMetric("Wolt", wolt.count.toString(), dashAveragePrice(wolt), BrandCyan, Modifier.weight(1f), onHistory)
                DashboardMetric("Bolt", bolt.count.toString(), dashAveragePrice(bolt), Success, Modifier.weight(1f), onHistory)
            }
        }

        item { DashboardSection("Recent days", "Captured offers by day") }
        if (days.isEmpty()) {
            item { DashboardEmpty("No daily statistics yet.") }
        } else {
            items(days, key = { it.day }) { day ->
                Card(onClick = onHistory, shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(day.day, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Wolt ${day.woltCount} · Bolt ${day.boltCount}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${day.count} offers", fontWeight = FontWeight.SemiBold)
                            Text(
                                day.averagePriceCents?.let { "€%.2f avg".format(it / 100.0) } ?: "—",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onHistory, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.History, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("History")
                }
                FilledTonalButton(onClick = onAddresses, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Place, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Addresses")
                }
            }
        }
    }
}

@Composable
private fun StatsPeriod(
    label: String,
    summary: OfferSummary,
    work: AutomaticWorkSummary,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Icon(Icons.Rounded.ChevronRight, contentDescription = null)
            }
            Row {
                Text("Offers", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(summary.count.toString(), fontWeight = FontWeight.Medium)
            }
            Row {
                Text("Average offer", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(dashAveragePrice(summary))
            }
            Row {
                Text("Avg €/km", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(dashPerKm(summary))
            }
            Row {
                Text("Online time", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(dashDuration(work.totalMillis))
            }
            Row {
                Text("Offers / hour", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(dashOffersPerHour(summary.count, work.totalMillis))
            }
        }
    }
}

@Composable
private fun DashboardSettings(
    notificationOk: Boolean,
    accessibilityOk: Boolean,
    padding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var autoOpen by remember { mutableStateOf(OfferState.autoOpen(context)) }
    var wakeScreen by remember { mutableStateOf(OfferState.wakeScreen(context)) }
    var liveAdvisor by remember { mutableStateOf(LiveAdvisorSettings.enabled(context)) }
    var voice by remember { mutableStateOf(LiveAdvisorSettings.voiceEnabled(context)) }
    var woltRoute by remember { mutableStateOf(LiveAdvisorSettings.automaticWoltRouting(context)) }
    var boltRoute by remember { mutableStateOf(LiveAdvisorSettings.automaticBoltRouting(context)) }
    var saveScreenshots by remember { mutableStateOf(CaptureStorageSettings.saveOfferScreenshots(context)) }
    var developerTaps by remember { mutableIntStateOf(0) }
    var developerEnabled by remember { mutableStateOf(DeveloperModeSettings.enabled(context)) }
    val routeReady = runCatching { RouteEndpointSettings.load(context).validated() }.isSuccess

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 12.dp, 16.dp, padding.calculateBottomPadding() + 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    Text("Offers, routes, storage and Android access", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onBack) { Text("Done") }
            }
        }

        item { DashboardSection("Offers", "What CourierPilot does when an offer appears") }
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp)) {
                    SettingsSwitchRow("Live offer card", "Show price, ETA and calculated route metrics over Wolt/Bolt.", liveAdvisor) {
                        liveAdvisor = it
                        LiveAdvisorSettings.setEnabled(context, it)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    SettingsSwitchRow("Voice readout", "Read the compact offer summary aloud when the live card appears.", voice) {
                        voice = it
                        LiveAdvisorSettings.setVoiceEnabled(context, it)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    SettingsSwitchRow("Auto-open real offer notifications", "Strict classifier; unrelated notifications stay untouched.", autoOpen) {
                        autoOpen = it
                        OfferState.setAutoOpen(context, it)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    SettingsSwitchRow("Wake screen for offers", "Briefly wakes a sleeping screen after a matched offer.", wakeScreen) {
                        wakeScreen = it
                        OfferState.setWakeScreen(context, it)
                    }
                }
            }
        }

        item { DashboardSection("Calculated routes", if (routeReady) "Private route service ready" else "Route service needs developer provisioning") }
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp)) {
                    SettingsSwitchRow(
                        "Wolt calculated route",
                        if (routeReady) "Use phone GPS + visible Wolt stops for Valhalla comparison." else "Unavailable until the private route service is provisioned.",
                        woltRoute,
                        enabled = routeReady,
                    ) {
                        woltRoute = it
                        LiveAdvisorSettings.setAutomaticWoltRouting(context, it)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    SettingsSwitchRow(
                        "Bolt calculated route",
                        if (routeReady) "Calculate to pickup and recover customer map point only when evidence is sufficient." else "Unavailable until the private route service is provisioned.",
                        boltRoute,
                        enabled = routeReady,
                    ) {
                        boltRoute = it
                        LiveAdvisorSettings.setAutomaticBoltRouting(context, it)
                    }
                }
            }
        }

        item { DashboardSection("Storage", "OCR does not require saving images to your gallery") }
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp)) {
                    SettingsSwitchRow(
                        "Save offer screenshots",
                        "On by default. Turn it off only if you do not want PNG copies in Pictures/CourierOffers; OCR still works in memory.",
                        saveScreenshots,
                    ) {
                        saveScreenshots = it
                        CaptureStorageSettings.setSaveOfferScreenshots(context, it)
                    }
                }
            }
        }

        item { DashboardSection("Android access", "Required for background offer capture") }
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
            FilledTonalButton(
                onClick = { context.startActivity(Intent(context, ReliabilityActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Shield, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Reliability Center")
            }
        }

        if (developerEnabled) {
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
            TextButton(
                onClick = {
                    if (!developerEnabled) {
                        developerTaps++
                        if (developerTaps >= 7) {
                            DeveloperModeSettings.setEnabled(context, true)
                            developerEnabled = true
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "CourierPilot ${dashAppVersion(context)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun SettingsStatusCard(
    label: String,
    ok: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
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
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

@Composable
private fun DashboardMetric(
    label: String,
    value: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
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
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (record.platform == "Wolt") Color(0xFFE6F7FD) else Color(0xFFEAF8EE),
            ) {
                Icon(
                    Icons.Rounded.Storefront,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                    tint = if (record.platform == "Wolt") BrandCyan else Success,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    record.restaurant ?: record.merchantNames.firstOrNull() ?: record.platform,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val route = record.dropoffAddresses.firstOrNull() ?: record.pickupAddresses.firstOrNull()
                if (route != null) {
                    Text(route, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "${record.platform} · ${dashShortDate(record.capturedAt)}" +
                        (record.distanceMeters?.let { " · ${"%.1f".format(it / 1000.0)} km" } ?: ""),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            Text("€${"%.2f".format(record.priceCents / 100.0)}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.size(6.dp))
            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
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

@Composable
private fun PaginationRow(
    page: Int,
    pageCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onPrevious, enabled = page > 0) {
            Icon(Icons.Rounded.ChevronLeft, contentDescription = null)
            Text("Previous")
        }
        Text(
            "Page ${page + 1} of $pageCount",
            Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontSize = 12.sp,
        )
        TextButton(onClick = onNext, enabled = page + 1 < pageCount) {
            Text("Next")
            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
        }
    }
}

private fun dashStartOfDay(daysBack: Int): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, -daysBack)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun dashAveragePrice(summary: OfferSummary): String =
    summary.averagePriceCents?.let { "€%.2f".format(it / 100.0) } ?: "—"

private fun dashPerKm(summary: OfferSummary): String =
    summary.averageEurPerKm?.let { "€%.2f/km".format(it) } ?: "—"

private fun dashDuration(ms: Long): String {
    val minutes = (ms / 60_000L).coerceAtLeast(0L)
    val hours = minutes / 60L
    val rest = minutes % 60L
    return if (hours > 0) "${hours}h ${rest}m" else "${rest}m"
}

private fun dashOffersPerHour(offers: Int, workMillis: Long): String {
    if (workMillis < 60_000L) return "—"
    val hours = workMillis / 3_600_000.0
    return "%.1f".format(offers / hours)
}

private fun dashShortDate(timestamp: Long): String =
    SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun dashAppVersion(context: android.content.Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}.getOrDefault("")

private const val HISTORY_PAGE_SIZE = 50
private const val ADDRESS_PAGE_SIZE = 40
