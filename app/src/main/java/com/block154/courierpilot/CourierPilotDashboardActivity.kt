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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.ui.draw.clip
import com.block154.courierpilot.ui.AppearanceSettings
import com.block154.courierpilot.ui.FilterChipD
import com.block154.courierpilot.ui.GroupedBlock
import com.block154.courierpilot.ui.GroupedRow
import com.block154.courierpilot.ui.LocalCourierPalette
import com.block154.courierpilot.ui.PlatformBadge
import com.block154.courierpilot.ui.RateNumberFamily
import com.block154.courierpilot.ui.RateText
import com.block154.courierpilot.ui.ThemeMode
import com.block154.courierpilot.ui.VerdictEmoji
import com.block154.courierpilot.ui.rateColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Euro
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
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
import com.block154.courierpilot.ui.CourierPilotToggleRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            val refreshToken = refreshVersion.intValue
            CourierPilotTheme {
                DashboardRoot(
                    offers = OfferDatabase.get(this),
                    meta = CourierMetaDatabase.get(this),
                    notificationOk = hasNotificationAccess(),
                    accessibilityOk = hasAccessibilityAccess(),
                    refreshToken = refreshToken,
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

private enum class DashboardScreen { HOME, HISTORY, ADDRESSES, STATS, MARKET, SETTINGS }

@Composable
private fun DashboardRoot(
    offers: OfferDatabase,
    meta: CourierMetaDatabase,
    notificationOk: Boolean,
    accessibilityOk: Boolean,
    refreshToken: Int,
) {
    var screen by remember { mutableStateOf(DashboardScreen.HOME) }
    val historyState = remember { HistoryListState() }
    val context = LocalContext.current
    BackHandler(enabled = screen != DashboardScreen.HOME) { screen = DashboardScreen.HOME }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (screen != DashboardScreen.SETTINGS) {
                DashboardFloatingNav(screen) { screen = it }
            }
        },
    ) { padding ->
        when (screen) {
            DashboardScreen.HOME -> DashboardHome(
                offers = offers,
                meta = meta,
                notificationOk = notificationOk,
                accessibilityOk = accessibilityOk,
                refreshToken = refreshToken,
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
            DashboardScreen.HISTORY -> DashboardHistory(offers, historyState, padding, refreshToken) { id ->
                context.startActivity(
                    Intent(context, OfferDetailsActivity::class.java)
                        .putExtra(OfferDetailsActivity.EXTRA_OFFER_ID, id)
                )
            }
            DashboardScreen.ADDRESSES -> DashboardAddresses(meta, padding, refreshToken) { id ->
                context.startActivity(
                    Intent(context, AddressDetailsActivity::class.java)
                        .putExtra(AddressDetailsActivity.EXTRA_ADDRESS_ID, id)
                )
            }
            DashboardScreen.STATS -> DashboardStats(
                offers = offers,
                meta = meta,
                padding = padding,
                refreshToken = refreshToken,
            )
            DashboardScreen.MARKET -> DashboardMarket(padding, refreshToken)
            DashboardScreen.SETTINGS -> DashboardSettings(notificationOk, accessibilityOk, padding, refreshToken) {
                screen = DashboardScreen.HOME
            }
        }
    }
}

private data class DashboardMarketData(
    val platformName: String,
    val periodKey: String,
    val currencyCode: String,
    val profile: MarketProfile?,
    val local: LocalMarketProfile?,
    val personalHistory: List<MarketHistoryBucket>,
    val cityHistory: List<MarketHistoryBucket>,
)

private fun loadDashboardMarketData(
    context: android.content.Context,
    platformName: String,
    periodKey: String,
): DashboardMarketData {
    val currencyCode = MarketIntelligence.currencyFor(context, platformName)
    val profile = MarketIntelligence.profileFor(context, platformName, currencyCode)
    val local = MarketIntelligence.localProfileFor(context, platformName, currencyCode)
    val personalHistory = MarketIntelligence.localHistoryFor(context, platformName, currencyCode, periodKey).map { point ->
        MarketHistoryBucket(
            label = point.bucket,
            median = "%.2f".format(Locale.getDefault(), point.medianNativeMoneyPerKm),
            p25 = "%.2f".format(Locale.getDefault(), point.p25),
            p75 = "%.2f".format(Locale.getDefault(), point.p75),
            sampleCount = point.sampleCount,
        )
    }
    val cityHistory = MarketIntelligence.cityHistoryFor(context, platformName, currencyCode, periodKey).map { point ->
        MarketHistoryBucket(
            label = point.bucket,
            median = "%.2f".format(Locale.getDefault(), point.medianNativeMoneyPerKm),
            p25 = "%.2f".format(Locale.getDefault(), point.p25),
            p75 = "%.2f".format(Locale.getDefault(), point.p75),
            sampleCount = point.sampleCount,
        )
    }
    return DashboardMarketData(platformName, periodKey, currencyCode, profile, local, personalHistory, cityHistory)
}

@Composable
private fun DashboardMarket(padding: PaddingValues, refreshToken: Int) {
    val context = LocalContext.current
    var platform by remember { mutableStateOf(MarketPlatform.WOLT) }
    var period by remember { mutableStateOf(MarketHistoryPeriod.WEEK) }
    var historyRevision by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<DashboardMarketData?>(null) }
    val platformName = if (platform == MarketPlatform.WOLT) "Wolt" else "Bolt"
    val periodKey = period.name.lowercase(Locale.ROOT)

    LaunchedEffect(platformName, periodKey, refreshToken) {
        data = withContext(Dispatchers.IO) { loadDashboardMarketData(context, platformName, periodKey) }
        val currencyCode = data?.currencyCode ?: return@LaunchedEffect
        MarketIntelligence.refreshHistory(context, platformName, currencyCode, periodKey) {
            historyRevision += 1
        }
    }

    LaunchedEffect(historyRevision) {
        if (historyRevision == 0) return@LaunchedEffect
        data = withContext(Dispatchers.IO) { loadDashboardMarketData(context, platformName, periodKey) }
    }

    val loaded = data?.takeIf { it.platformName == platformName && it.periodKey == periodKey }
    if (loaded == null) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DashboardSection("Pay comparison", "Loading local and city pay/km data") }
            item { DashboardEmpty("Loading pay insights…") }
        }
        return
    }

    val source = when {
        loaded.local != null && loaded.profile?.ready == true -> MarketSource.PERSONAL_AND_CITY
        loaded.local != null -> MarketSource.PERSONAL
        loaded.profile?.ready == true -> MarketSource.CITY
        else -> MarketSource.LEARNING
    }
    val confidence = when {
        loaded.local != null && loaded.local.sampleCount >= 25 -> MarketUiConfidence.HIGH
        loaded.local != null && loaded.local.sampleCount >= 10 -> MarketUiConfidence.MEDIUM
        loaded.local != null && loaded.local.sampleCount >= 5 -> MarketUiConfidence.LOW
        loaded.profile?.confidence?.equals("HIGH", true) == true -> MarketUiConfidence.HIGH
        loaded.profile?.confidence?.equals("MEDIUM", true) == true -> MarketUiConfidence.MEDIUM
        loaded.profile?.confidence?.equals("LOW", true) == true -> MarketUiConfidence.LOW
        else -> MarketUiConfidence.NOT_READY
    }
    val state = MarketScreenState(
        platform = platform,
        currencyCode = loaded.currencyCode,
        personalMedian = loaded.local?.medianNativeMoneyPerKm?.let { MarketMedian("%.2f".format(Locale.getDefault(), it), loaded.currencyCode) },
        cityMedian = loaded.profile?.medianNativeMoneyPerKm?.let { MarketMedian("%.2f".format(Locale.getDefault(), it), loaded.currencyCode) },
        source = source,
        confidence = confidence,
        sampleCount = loaded.local?.sampleCount ?: loaded.profile?.sampleCount ?: 0,
        trend = loaded.profile?.trend?.let { MarketUiTrend(percent = it.percent, improving = it.direction == "up") },
        period = period,
        personalHistory = loaded.personalHistory,
        cityHistory = loaded.cityHistory,
    )
    MarketScreen(
        state = state,
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 20.dp,
        ),
        onPlatformSelected = { platform = it },
        onPeriodSelected = { period = it },
    )
}


private data class DashboardHomeData(
    val presence: List<PlatformPresence>,
    val work: AutomaticWorkSummary,
    val today: DashboardMoneySummary,
    val best: OfferRowRate?,
    val recent: List<OfferRecord>,
)

@Composable
private fun DashboardHome(
    offers: OfferDatabase,
    meta: CourierMetaDatabase,
    notificationOk: Boolean,
    accessibilityOk: Boolean,
    refreshToken: Int,
    padding: PaddingValues,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onStats: () -> Unit,
    onOpenOffer: (Long) -> Unit,
) {
    val context = LocalContext.current
    var data by remember { mutableStateOf<DashboardHomeData?>(null) }

    LaunchedEffect(refreshToken) {
        data = withContext(Dispatchers.IO) {
            val startOfDay = dashStartOfDay(0)
            DashboardHomeData(
                presence = CourierPresence.all(context),
                work = meta.workSummarySince(startOfDay),
                today = DashboardMoneyStats.summarySince(offers, startOfDay),
                best = offers.recordsSince(startOfDay, limit = 500)
                    .mapNotNull(OfferRowRatePolicy::rate)
                    .maxByOrNull { it.perKm },
                recent = offers.recent(4).map(OfferEnrichmentCache::enriched),
            )
        }
    }

    val loaded = data
    val listPadding = PaddingValues(
        start = 16.dp,
        end = 16.dp,
        top = padding.calculateTopPadding() + 8.dp,
        bottom = padding.calculateBottomPadding() + 20.dp,
    )
    if (loaded == null) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = listPadding,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { DashboardScreenTitle("Today", SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(Date())) }
            item { Spacer(Modifier.height(14.dp)); DashboardEmpty("Loading today’s offers and work time…") }
        }
        return
    }

    val offersPerHour = dashOffersPerHour(loaded.today.count, loaded.work.totalMillis)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = listPadding,
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(Date()),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Text("Today", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                }
                PresencePill(loaded.presence, dashDuration(loaded.work.totalMillis), loaded.work.active)
                Spacer(Modifier.size(8.dp))
                SquareIconButton(Icons.Rounded.Settings, "Settings", onSettings)
            }
        }

        if (!notificationOk || !accessibilityOk) {
            item {
                Spacer(Modifier.height(14.dp))
                Surface(
                    onClick = onSettings,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WarningAmber, contentDescription = null)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Capture needs attention", fontWeight = FontWeight.Bold)
                            Text("Open settings to restore Android access.", fontSize = 12.sp)
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            TodayHeroCard(loaded.today, offersPerHour, loaded.best, onStats)
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 22.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Recent offers", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "See all",
                    color = LocalCourierPalette.current.accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable(onClick = onHistory).padding(4.dp),
                )
            }
        }
        if (loaded.recent.isEmpty()) {
            item { DashboardEmpty("No priced offers captured yet.") }
        } else {
            itemsIndexed(loaded.recent, key = { _, record -> record.id }) { index, record ->
                DashboardOfferRow(record, index, loaded.recent.size, showPickup = false) { onOpenOffer(record.id) }
            }
        }
    }
}

@Composable
private fun TodayHeroCard(
    today: DashboardMoneySummary,
    offersPerHour: String,
    best: OfferRowRate?,
    onClick: () -> Unit,
) {
    val palette = LocalCourierPalette.current
    val avgRate = today.averageMoneyPerKm?.takeUnless { today.mixedCurrency }
    val avgGrade = OfferRowRatePolicy.gradeFor(avgRate, today.currencyCode)
    GroupedBlock(Modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Average offer today", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (avgRate != null) {
                    RateText(
                        OfferRowRatePolicy.formatValue(avgRate, today.currencyCode),
                        "/km",
                        avgGrade,
                        valueSize = 46.sp,
                        unitSize = 16.sp,
                    )
                    Spacer(Modifier.size(8.dp))
                    VerdictEmoji(avgGrade, size = 24.sp)
                } else {
                    Text("—", fontFamily = RateNumberFamily, fontSize = 46.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val avgOffer = dashAveragePrice(today)
            if (avgOffer != "—") {
                Text("$avgOffer per offer", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroMiniStat("Offers", Modifier.weight(1f), palette.miniStatBg) {
                    Text(today.count.toString(), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                }
                HeroMiniStat("Per hour", Modifier.weight(1f), palette.miniStatBg) {
                    Text(offersPerHour, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                }
                HeroMiniStat("Best", Modifier.weight(1f), palette.bestBg) {
                    if (best == null) {
                        Text("—", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                best.value,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = palette.rateColor(best.grade),
                                maxLines = 1,
                                softWrap = false,
                            )
                            Spacer(Modifier.size(4.dp))
                            VerdictEmoji(best.grade, size = 15.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroMiniStat(
    label: String,
    modifier: Modifier,
    background: Color,
    value: @Composable () -> Unit,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = background) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 1)
            value()
        }
    }
}

@Composable
private fun PresencePill(presence: List<PlatformPresence>, workTime: String, active: Boolean) {
    val palette = LocalCourierPalette.current
    val online = presence.filter { it.state == PresenceSignal.ONLINE }.map { it.platform }
    val isOnline = online.isNotEmpty() || active
    val label = when {
        online.size == 1 -> "${online.first()} · $workTime"
        online.isNotEmpty() || active -> "Online · $workTime"
        else -> "Offline · $workTime"
    }
    Surface(shape = RoundedCornerShape(50), color = if (isOnline) palette.onlineBg else palette.offlineBg) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (isOnline) Success else palette.offlineText, RoundedCornerShape(50))
            )
            Spacer(Modifier.size(6.dp))
            Text(
                label,
                color = if (isOnline) palette.onlineText else palette.offlineText,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SquareIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = LocalCourierPalette.current.iconBg,
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
        }
    }
}

private enum class HistoryPlatformFilter(val label: String, val platform: String?) {
    ALL("All", null),
    WOLT("Wolt", "Wolt"),
    BOLT("Bolt", "Bolt"),
}

private data class HistoryDayGroup(
    val label: String,
    val records: List<OfferRecord>,
)

/**
 * History list state, hoisted to [DashboardRoot] so switching tabs keeps the loaded rows, scroll
 * position, query and filter instead of starting again from "Loading offers…".
 */
private class HistoryListState {
    var query by mutableStateOf("")
    var filter by mutableStateOf(HistoryPlatformFilter.ALL)
    var records by mutableStateOf<List<OfferRecord>>(emptyList())
    var total by mutableIntStateOf(0)
    var loadedOnce by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var endReached by mutableStateOf(false)
    val listState = LazyListState()

    val canLoadMore: Boolean get() = !endReached && !loadingMore
}

/**
 * Loads [count] rows from [offset]. Stored rows are shown at once (they already carry the
 * capture-time structure); only uncached rows are re-parsed, in parallel, and swapped in after.
 */
private suspend fun HistoryListState.loadRange(
    offers: OfferDatabase,
    offset: Int,
    count: Int,
    replace: Boolean,
) {
    val query = query
    val platform = filter.platform
    val (newTotal, stored) = withContext(Dispatchers.IO) {
        offers.offerCount(query, platform) to offers.searchPage(query, count, offset, platform)
    }
    if (query != this.query || platform != filter.platform) return
    total = newTotal
    endReached = offset + stored.size >= newTotal || stored.isEmpty()
    val instant = stored.map { OfferEnrichmentCache.cached(it) ?: it }
    records = if (replace) instant else (records + instant).distinctBy { it.id }
    loadedOnce = true
    if (stored.none { OfferEnrichmentCache.cached(it) == null }) return
    val enriched = OfferEnrichmentCache.enrichAll(stored).associateBy { it.id }
    if (query != this.query || platform != filter.platform) return
    records = records.map { enriched[it.id] ?: it }
}

@Composable
private fun DashboardHistory(
    offers: OfferDatabase,
    state: HistoryListState,
    padding: PaddingValues,
    refreshToken: Int,
    onOpenOffer: (Long) -> Unit,
) {
    val scope = rememberCoroutineScope()

    // First page for a new query/filter, and a silent refresh of what is already shown on resume.
    LaunchedEffect(state.query, state.filter, refreshToken) {
        if (state.query.isNotBlank()) delay(200L)
        state.loadRange(
            offers = offers,
            offset = 0,
            count = state.records.size.coerceIn(HISTORY_CHUNK_SIZE, HISTORY_CHUNK_SIZE * 6),
            replace = true,
        )
    }

    val nearEnd by remember {
        derivedStateOf {
            val info = state.listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, state.records.size) {
        if (!nearEnd || !state.loadedOnce || !state.canLoadMore) return@LaunchedEffect
        state.loadingMore = true
        try {
            state.loadRange(offers, offset = state.records.size, count = HISTORY_CHUNK_SIZE, replace = false)
        } finally {
            state.loadingMore = false
        }
    }

    val groups = remember(state.records) { historyDayGroups(state.records) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = state.listState,
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, padding.calculateBottomPadding() + 20.dp),
    ) {
        item(key = "title") { DashboardScreenTitle("History", if (state.loadedOnce) "${state.total} captured offers" else "Offer history") }
        item(key = "search") {
            Spacer(Modifier.height(12.dp))
            DashboardSearchField(state.query, "Venue, address, customer…") {
                state.query = it
                scope.launch { state.listState.scrollToItem(0) }
            }
        }
        item(key = "filters") {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryPlatformFilter.entries.forEach { option ->
                    FilterChipD(option.label, selected = state.filter == option) {
                        state.filter = option
                        scope.launch { state.listState.scrollToItem(0) }
                    }
                }
            }
        }
        when {
            !state.loadedOnce -> item(key = "loading") { Spacer(Modifier.height(12.dp)); DashboardEmpty("Loading offers…") }
            state.records.isEmpty() -> item(key = "empty") {
                Spacer(Modifier.height(12.dp))
                DashboardEmpty(
                    if (state.query.isBlank() && state.filter == HistoryPlatformFilter.ALL) "No offers yet."
                    else "No offers match this search."
                )
            }
            else -> groups.forEach { group ->
                item(key = "day-${group.label}-${group.records.first().id}") { HistoryDayHeader(group) }
                itemsIndexed(group.records, key = { _, record -> record.id }) { index, record ->
                    DashboardOfferRow(record, index, group.records.size, showPickup = true) { onOpenOffer(record.id) }
                }
            }
        }
        if (state.loadingMore) {
            item(key = "more") {
                Text(
                    "Loading more…",
                    Modifier.fillMaxWidth().padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

private fun historyDayGroups(records: List<OfferRecord>): List<HistoryDayGroup> {
    val today = dashStartOfDay(0)
    val yesterday = dashStartOfDay(1)
    val dayFormat = SimpleDateFormat("EEEE, d MMM", Locale.getDefault())
    return records
        .groupBy { record ->
            when {
                record.capturedAt >= today -> "Today"
                record.capturedAt >= yesterday -> "Yesterday"
                else -> dayFormat.format(Date(record.capturedAt))
            }
        }
        .map { (label, dayRecords) -> HistoryDayGroup(label, dayRecords) }
}

@Composable
private fun HistoryDayHeader(group: HistoryDayGroup) {
    val rates = group.records.mapNotNull(OfferRowRatePolicy::rate)
    val currencies = group.records.map { it.currencyCode }.distinct()
    val average = rates.takeIf { it.isNotEmpty() && currencies.size == 1 }?.map { it.perKm }?.average()
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(group.label, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            buildString {
                append("${group.records.size} offers")
                if (average != null) append(" · avg ${OfferRowRatePolicy.formatValue(average, currencies.first())}/km")
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun DashboardSearchField(value: String, placeholder: String, onValueChange: (String) -> Unit) {
    val palette = LocalCourierPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = palette.chipBorder,
        ),
    )
}

private data class DashboardAddressRow(
    val address: AddressRecord,
    val codes: List<String>,
)

@Composable
private fun DashboardAddresses(
    meta: CourierMetaDatabase,
    padding: PaddingValues,
    refreshToken: Int,
    onOpenAddress: (Long) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<DashboardAddressRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val context = LocalContext.current

    LaunchedEffect(query, page, refreshToken) {
        loading = true
        if (query.isNotBlank()) delay(160L)
        val requestedPage = page
        val loaded = withContext(Dispatchers.IO) {
            val count = meta.addressCount(query)
            val pageCount = maxOf(1, ceil(count / ADDRESS_PAGE_SIZE.toDouble()).toInt())
            val safePage = requestedPage.coerceIn(0, pageCount - 1)
            val pageRows = meta.searchAddresses(query, ADDRESS_PAGE_SIZE, safePage * ADDRESS_PAGE_SIZE).map { address ->
                DashboardAddressRow(
                    address = address,
                    codes = meta.codesForBuilding(address.buildingKey, 3).map { it.code }.distinct(),
                )
            }
            Triple(count, safePage, pageRows)
        }
        total = loaded.first
        if (page != loaded.second) page = loaded.second
        rows = loaded.third
        loading = false
    }

    val pageCount = maxOf(1, ceil(total / ADDRESS_PAGE_SIZE.toDouble()).toInt())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, padding.calculateBottomPadding() + 20.dp),
    ) {
        item { DashboardScreenTitle("Addresses", "$total buildings saved on this phone") }
        item {
            Spacer(Modifier.height(12.dp))
            DashboardSearchField(query, "Search street, name or code") {
                query = it
                page = 0
            }
            Spacer(Modifier.height(12.dp))
        }
        when {
            loading && rows.isEmpty() -> item { DashboardEmpty("Loading addresses…") }
            rows.isEmpty() -> item { DashboardEmpty(if (query.isBlank()) "No addresses captured yet." else "No addresses match this search.") }
            else -> itemsIndexed(rows, key = { _, row -> row.address.id }) { index, row ->
                DashboardAddressItem(
                    row = row,
                    index = index,
                    count = rows.size,
                    onOpen = { onOpenAddress(row.address.id) },
                    onMap = { context.openAddressInMaps(row.address.displayAddress) },
                )
            }
        }
        if (total > ADDRESS_PAGE_SIZE) {
            item {
                Spacer(Modifier.height(8.dp))
                PaginationRow(
                    page = page,
                    pageCount = pageCount,
                    onPrevious = { if (!loading && page > 0) page-- },
                    onNext = { if (!loading && page + 1 < pageCount) page++ },
                )
            }
        }
    }
}

@Composable
private fun DashboardAddressItem(
    row: DashboardAddressRow,
    index: Int,
    count: Int,
    onOpen: () -> Unit,
    onMap: () -> Unit,
) {
    val palette = LocalCourierPalette.current
    val address = row.address
    GroupedRow(index = index, count = count, onClick = onOpen) {
        Surface(shape = RoundedCornerShape(12.dp), color = palette.pinBg, modifier = Modifier.size(38.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Place, contentDescription = null, tint = palette.pinText, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                address.displayAddress,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    address.latestCustomerName?.takeIf(String::isNotBlank),
                    address.platform.takeIf(String::isNotBlank),
                    "seen ${address.seenCount}×",
                ).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        row.codes.firstOrNull()?.let { code ->
            Surface(shape = RoundedCornerShape(9.dp), color = palette.codeBg) {
                Text(
                    "🔑 $code" + if (row.codes.size > 1) " +${row.codes.size - 1}" else "",
                    color = palette.codeText,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
        }
        SquareIconButton(Icons.Rounded.Map, "Open in maps", onMap)
    }
}

private enum class StatsPeriod(val label: String, val daysBack: Int) {
    TODAY("Today", 0),
    WEEK("7 days", 6),
    MONTH("30 days", 29),
}

private data class StatsPeriodData(
    val summary: DashboardMoneySummary,
    val work: AutomaticWorkSummary,
    val wolt: DashboardMoneySummary,
    val bolt: DashboardMoneySummary,
)

private data class DashboardStatsData(
    val periods: Map<StatsPeriod, StatsPeriodData>,
    val days: List<DashboardMoneyDaySummary>,
)

@Composable
private fun DashboardStats(
    offers: OfferDatabase,
    meta: CourierMetaDatabase,
    padding: PaddingValues,
    refreshToken: Int,
) {
    var stats by remember { mutableStateOf<DashboardStatsData?>(null) }
    var period by remember { mutableStateOf(StatsPeriod.WEEK) }

    LaunchedEffect(refreshToken) {
        stats = withContext(Dispatchers.IO) {
            DashboardStatsData(
                periods = StatsPeriod.entries.associateWith { p ->
                    val since = dashStartOfDay(p.daysBack)
                    StatsPeriodData(
                        summary = DashboardMoneyStats.summarySince(offers, since),
                        work = meta.workSummarySince(since),
                        wolt = DashboardMoneyStats.summarySince(offers, since, "Wolt"),
                        bolt = DashboardMoneyStats.summarySince(offers, since, "Bolt"),
                    )
                },
                days = DashboardMoneyStats.dailyStats(offers, 14),
            )
        }
    }

    val loaded = stats
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, padding.calculateBottomPadding() + 20.dp),
    ) {
        item { DashboardScreenTitle("Stats", "Offers captured on this phone") }
        item {
            Row(Modifier.padding(top = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsPeriod.entries.forEach { option ->
                    FilterChipD(option.label, selected = period == option) { period = option }
                }
            }
        }
        if (loaded == null) {
            item { DashboardEmpty("Loading statistics…") }
            return@LazyColumn
        }
        val data = loaded.periods.getValue(period)
        item { StatsHeroCard(period, data) }

        item { StatsSectionLabel("Platforms") }
        val platforms = listOf("Wolt" to data.wolt, "Bolt" to data.bolt)
        itemsIndexed(platforms, key = { _, item -> "platform-${item.first}" }) { index, (name, summary) ->
            GroupedRow(index = index, count = platforms.size) {
                PlatformBadge(name)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        if (summary.count == 0) "No offers" else "${summary.count} offers · ${dashAveragePrice(summary)} per offer",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.5.sp,
                    )
                }
                StatsRate(summary.averageMoneyPerKm.takeUnless { summary.mixedCurrency }, summary.currencyCode)
            }
        }

        item { StatsSectionLabel("Last 14 days") }
        if (loaded.days.isEmpty()) {
            item { DashboardEmpty("No daily statistics yet.") }
        } else {
            item { StatsDayBars(loaded.days) }
            item { Spacer(Modifier.height(10.dp)) }
            itemsIndexed(loaded.days, key = { _, day -> "day-${day.day}" }) { index, day ->
                GroupedRow(index = index, count = loaded.days.size) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(statsDayLabel(day.day), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            "${day.count} offers · Wolt ${day.woltCount} · Bolt ${day.boltCount}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.5.sp,
                        )
                    }
                    StatsRate(day.averageMoneyPerKm.takeUnless { day.mixedCurrency }, day.currencyCode)
                }
            }
        }
    }
}

@Composable
private fun StatsHeroCard(period: StatsPeriod, data: StatsPeriodData) {
    val palette = LocalCourierPalette.current
    val summary = data.summary
    val rate = summary.averageMoneyPerKm?.takeUnless { summary.mixedCurrency }
    val grade = OfferRowRatePolicy.gradeFor(rate, summary.currencyCode)
    GroupedBlock {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Average €/km · ${period.label.lowercase(Locale.getDefault())}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rate != null) {
                    RateText(OfferRowRatePolicy.formatValue(rate, summary.currencyCode), "/km", grade, valueSize = 46.sp, unitSize = 16.sp)
                    Spacer(Modifier.size(8.dp))
                    VerdictEmoji(grade, size = 24.sp)
                } else {
                    Text("—", fontFamily = RateNumberFamily, fontSize = 46.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val avgOffer = dashAveragePrice(summary)
            if (avgOffer != "—") {
                Text("$avgOffer per offer", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsMini("Offers", summary.count.toString(), Modifier.weight(1f), palette.miniStatBg)
                StatsMini("Online", dashDuration(data.work.totalMillis), Modifier.weight(1f), palette.miniStatBg)
                StatsMini("Per hour", dashOffersPerHour(summary.count, data.work.totalMillis), Modifier.weight(1f), palette.miniStatBg)
            }
            summary.averageDistanceMeters?.let { meters ->
                Text(
                    "Average route ${"%.1f".format(Locale.US, meters / 1000.0)} km",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun StatsMini(label: String, value: String, modifier: Modifier, background: Color) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = background) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 1)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
    }
}

@Composable
private fun StatsRate(perKm: Double?, currencyCode: String?) {
    if (perKm == null) {
        Text("—", fontFamily = RateNumberFamily, fontSize = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val grade = OfferRowRatePolicy.gradeFor(perKm, currencyCode)
    RateText(OfferRowRatePolicy.formatValue(perKm, currencyCode), "/km", grade)
    VerdictEmoji(grade)
}

@Composable
private fun StatsSectionLabel(text: String) {
    Text(
        text.uppercase(Locale.getDefault()),
        modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.6.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Offers per day as bars; each bar takes the verdict colour of that day's average €/km. */
@Composable
private fun StatsDayBars(days: List<DashboardMoneyDaySummary>) {
    val palette = LocalCourierPalette.current
    val byDay = days.associateBy { it.day }
    val keyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val letterFormat = SimpleDateFormat("EEE", Locale.getDefault())
    val slots = (13 downTo 0).map { back ->
        val date = Date(dashStartOfDay(back))
        Triple(byDay[keyFormat.format(date)], letterFormat.format(date).take(2), back == 0)
    }
    val maxCount = slots.maxOf { it.first?.count ?: 0 }.coerceAtLeast(1)
    GroupedBlock {
        Row(
            Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            slots.forEach { (day, letter, today) ->
                val count = day?.count ?: 0
                val grade = OfferRowRatePolicy.gradeFor(day?.averageMoneyPerKm.takeUnless { day?.mixedCurrency == true }, day?.currencyCode)
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (count > 0) count.toString() else "",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight((count.toFloat() / maxCount).coerceAtLeast(if (count > 0) 0.04f else 0.015f))
                                .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                                .background(if (count > 0) palette.rateColor(grade) else palette.line)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        letter,
                        maxLines = 1,
                        softWrap = false,
                        fontSize = 10.sp,
                        fontWeight = if (today) FontWeight.ExtraBold else FontWeight.Medium,
                        color = if (today) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun statsDayLabel(day: String): String {
    val date = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day) }.getOrNull() ?: return day
    return when (date.time) {
        dashStartOfDay(0) -> "Today"
        dashStartOfDay(1) -> "Yesterday"
        else -> SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(date)
    }
}

@Composable
private fun DashboardSettings(
    notificationOk: Boolean,
    accessibilityOk: Boolean,
    padding: PaddingValues,
    refreshToken: Int,
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
    var marketSharing by remember { mutableStateOf(MarketIntelligence.sharingEnabled(context)) }
    var remoteDiagnostics by remember { mutableStateOf(RemoteDiagnostics.enabled(context)) }
    var remoteStatus by remember {
        mutableStateOf(
            RemoteDiagnosticsStatus(
                enabled = remoteDiagnostics,
                queued = 0,
                lastUploadAt = 0L,
                lastError = "",
            )
        )
    }
    var developerTaps by remember { mutableIntStateOf(0) }
    var developerEnabled by remember { mutableStateOf(DeveloperModeSettings.enabled(context)) }
    val routeReady = runCatching { RouteEndpointSettings.load(context).validated() }.isSuccess
    var marketStatus by remember { mutableStateOf<MarketIntelligenceStatus?>(null) }

    LaunchedEffect(marketSharing, refreshToken) {
        marketStatus = withContext(Dispatchers.IO) { MarketIntelligence.status(context) }
    }

    LaunchedEffect(remoteDiagnostics, refreshToken) {
        while (true) {
            remoteStatus = withContext(Dispatchers.IO) { RemoteDiagnostics.status(context) }
            delay(5_000L)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, padding.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SquareIconButton(Icons.Rounded.ChevronLeft, "Back", onBack)
                Spacer(Modifier.size(12.dp))
                Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            }
        }

        item { StatsSectionLabel("Appearance") }
        item { AppearanceThemeGroup() }
        item { SettingsFootnote("The live card over Wolt and Bolt always stays dark, so it reads over any map.") }

        item { StatsSectionLabel("Offers") }
        item {
            SettingsGroup {
                SettingsSwitchRow("Live offer card", "Show price, ETA and calculated route metrics over Wolt/Bolt.", liveAdvisor) {
                    liveAdvisor = it
                    LiveAdvisorSettings.setEnabled(context, it)
                }
                SettingsDivider()
                SettingsSwitchRow("Voice readout", "Read the compact offer summary aloud when the live card appears.", voice) {
                    voice = it
                    LiveAdvisorSettings.setVoiceEnabled(context, it)
                }
                SettingsDivider()
                SettingsSwitchRow("Auto-open real offer notifications", "Strict classifier; unrelated notifications stay untouched.", autoOpen) {
                    autoOpen = it
                    OfferState.setAutoOpen(context, it)
                }
                SettingsDivider()
                SettingsSwitchRow("Wake screen for offers", "Briefly wakes a sleeping screen after a matched offer.", wakeScreen) {
                    wakeScreen = it
                    OfferState.setWakeScreen(context, it)
                }
            }
        }

        item { StatsSectionLabel("Routing") }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    "Wolt calculated route",
                    if (routeReady) "Use phone GPS + visible Wolt stops for Valhalla comparison." else "Unavailable until the private route service is provisioned.",
                    woltRoute,
                    enabled = routeReady,
                ) {
                    woltRoute = it
                    LiveAdvisorSettings.setAutomaticWoltRouting(context, it)
                }
                SettingsDivider()
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
        item { SettingsFootnote(if (routeReady) "Private route service ready." else "Route service needs developer provisioning.") }

        item { StatsSectionLabel("Pay comparison") }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    "Share anonymous market data",
                    "City, platform, price and calculated Valhalla kilometres only. No addresses, names, screenshots or exact GPS.",
                    marketSharing,
                ) { enabled ->
                    if (MarketIntelligence.setSharingEnabled(context, enabled)) {
                        marketSharing = enabled
                    } else {
                        marketSharing = MarketIntelligence.sharingEnabled(context)
                    }
                }
                SettingsDivider()
                Column(Modifier.padding(horizontal = 4.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        marketStatus?.city?.name ?: if (marketStatus == null) "Loading pay profile…" else "City not resolved yet",
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        marketStatus?.let { marketProfileSummary("Wolt", it.localWoltProfile, it.woltProfile) } ?: "Wolt · loading…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.5.sp,
                    )
                    Text(
                        marketStatus?.let { marketProfileSummary("Bolt", it.localBoltProfile, it.boltProfile) } ?: "Bolt · loading…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.5.sp,
                    )
                }
            }
        }

        item { StatsSectionLabel("Screenshots") }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    "Save offer screenshots",
                    "Save PNG copies in Pictures/CourierOffers. OCR continues to work when this is off.",
                    saveScreenshots,
                ) {
                    saveScreenshots = it
                    CaptureStorageSettings.setSaveOfferScreenshots(context, it)
                }
            }
        }

        item { StatsSectionLabel("Android access") }
        item {
            SettingsLinkRow(
                index = 0,
                count = 2,
                icon = Icons.Rounded.NotificationsActive,
                title = "Notification access",
                subtitle = if (notificationOk) "Enabled" else "Needs setup",
                subtitleColor = if (notificationOk) Success else MaterialTheme.colorScheme.error,
            ) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        item {
            SettingsLinkRow(
                index = 1,
                count = 2,
                icon = Icons.Rounded.Shield,
                title = "Accessibility capture",
                subtitle = if (accessibilityOk) "Enabled" else "Needs setup",
                subtitleColor = if (accessibilityOk) Success else MaterialTheme.colorScheme.error,
            ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        item { StatsSectionLabel("Diagnostics") }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    "Send diagnostics",
                    "Uploads app lifecycle, capture, route and performance events. No screenshots, addresses, customer text or exact GPS.",
                    remoteDiagnostics,
                ) { enabled ->
                    if (RemoteDiagnostics.setEnabled(context, enabled)) {
                        remoteDiagnostics = enabled
                        // The LaunchedEffect above refreshes queue/upload health on Dispatchers.IO.
                        // Keep the tap path free of JSON queue parsing.
                        remoteStatus = remoteStatus.copy(
                            enabled = enabled,
                            queued = if (enabled) remoteStatus.queued else 0,
                            lastError = if (enabled) remoteStatus.lastError else "",
                        )
                    } else {
                        remoteDiagnostics = RemoteDiagnostics.enabled(context)
                    }
                }
                SettingsDivider()
                Column(Modifier.padding(horizontal = 4.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Server logging", fontWeight = FontWeight.Bold)
                    Text(
                        diagnosticsStatusText(remoteStatus),
                        color = if (remoteStatus.lastError.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        fontSize = 12.5.sp,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
        item {
            SettingsLinkRow(
                index = 0,
                count = if (developerEnabled) 2 else 1,
                icon = Icons.Rounded.Shield,
                title = "Reliability Center",
                subtitle = "Capture health and an exportable report",
            ) { context.startActivity(Intent(context, ReliabilityActivity::class.java)) }
        }
        if (developerEnabled) {
            item {
                SettingsLinkRow(
                    index = 1,
                    count = 2,
                    icon = Icons.Rounded.BugReport,
                    title = "Developer tools",
                    subtitle = "Route research and debug switches",
                ) { context.startActivity(Intent(context, DeveloperToolsActivity::class.java)) }
            }
        }

        item { StatsSectionLabel("App updates") }
        item { AppUpdateSettingsSummaryCard() }

        item {
            Text(
                "CourierPilot ${dashAppVersion(context)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (!developerEnabled) {
                            developerTaps++
                            if (developerTaps >= 7) {
                                DeveloperModeSettings.setEnabled(context, true)
                                developerEnabled = true
                            }
                        }
                    }
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    GroupedBlock {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { content() }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = LocalCourierPalette.current.line, thickness = 1.dp)
}

@Composable
private fun SettingsFootnote(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
    )
}

@Composable
private fun SettingsLinkRow(
    index: Int,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    subtitleColor: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    val palette = LocalCourierPalette.current
    GroupedRow(index = index, count = count, onClick = onClick) {
        Surface(shape = RoundedCornerShape(12.dp), color = palette.pinBg, modifier = Modifier.size(38.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = palette.pinText, modifier = Modifier.size(20.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                subtitle,
                color = if (subtitleColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else subtitleColor,
                fontSize = 12.5.sp,
            )
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppearanceThemeGroup() {
    val context = LocalContext.current
    val selected = AppearanceSettings.themeMode(context)
    Column {
        ThemeMode.entries.forEachIndexed { index, mode ->
            GroupedRow(
                index = index,
                count = ThemeMode.entries.size,
                onClick = { AppearanceSettings.setThemeMode(context, mode) },
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(mode.label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(mode.hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp)
                }
                RadioButton(
                    selected = selected == mode,
                    onClick = { AppearanceSettings.setThemeMode(context, mode) },
                )
            }
        }
    }
}

private fun diagnosticsStatusText(status: RemoteDiagnosticsStatus): String = when {
    !status.enabled -> "Off · nothing is uploaded"
    status.lastError.isNotBlank() -> "Upload error: ${status.lastError} · ${status.queued} queued"
    status.lastUploadAt > 0L -> {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(status.lastUploadAt))
        "Connected · last upload $time · ${status.queued} queued"
    }
    else -> "Enabled · waiting for the first upload · ${status.queued} queued"
}

private fun formatMarketMoneyRate(value: Double, currencyCode: String): String =
    if (currencyCode == "EUR") "€${"%.2f".format(Locale.US, value)}"
    else "$currencyCode ${"%.2f".format(Locale.US, value)}"

private fun marketProfileSummary(
    platform: String,
    local: LocalMarketProfile?,
    city: MarketProfile?,
): String {
    val localPart = local?.let {
        val code = city?.currencyCode ?: "EUR"
        "you ${formatMarketMoneyRate(it.medianNativeMoneyPerKm, code)}/km · ${it.sampleCount} local"
    } ?: "you · learning"
    val cityPart = city?.let { profile ->
        val median = profile.medianNativeMoneyPerKm?.let { "${formatMarketMoneyRate(it, profile.currencyCode)}/km" } ?: "—"
        val trend = profile.trend?.let {
            val arrow = when (it.direction) {
                "up" -> "↑"
                "down" -> "↓"
                else -> "→"
            }
            " $arrow${if (it.percent >= 0) "+" else ""}${"%.1f".format(Locale.US, it.percent)}%"
        }.orEmpty()
        "city $median$trend"
    } ?: "city · no data"
    return "$platform · $localPart · $cityPart"
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit,
) {
    CourierPilotToggleRow(
        title = title,
        subtitle = subtitle,
        checked = checked,
        enabled = enabled,
        onCheckedChange = onChecked,
    )
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
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DashboardOfferRow(
    record: OfferRecord,
    index: Int,
    count: Int,
    showPickup: Boolean,
    onClick: () -> Unit,
) {
    val rate = remember(record) { OfferRowRatePolicy.rate(record) }
    GroupedRow(index = index, count = count, onClick = onClick) {
        PlatformBadge(record.platform)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                OfferPresentation.merchantSummary(record),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // Price and time first: on narrow phones the pickup street is what may ellipsize.
                listOfNotNull(
                    formatDashboardOfferMoney(record),
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(record.capturedAt)),
                    dashRowDistance(record),
                    record.pickupAddresses.firstOrNull()?.substringBefore(',')?.takeIf { showPickup && it.isNotBlank() },
                ).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (rate != null) {
            RateText(rate.value, rate.unit, rate.grade)
            VerdictEmoji(rate.grade)
        } else {
            Text(
                formatDashboardOfferMoney(record),
                fontFamily = RateNumberFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private fun dashRowDistance(record: OfferRecord): String? =
    record.distanceMeters?.takeIf { it > 0 }?.let { "%.1f km".format(Locale.US, it / 1000.0) }
        ?: record.trustedMarketRouteDistanceMeters?.let { "~%.1f km".format(Locale.US, it / 1000.0) }

@Composable
private fun DashboardScreenTitle(title: String, subtitle: String) {
    Column(Modifier.padding(start = 4.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun DashboardSection(title: String, subtitle: String) {
    Column(Modifier.padding(start = 4.dp, top = 6.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp)
    }
}

@Composable
private fun DashboardEmpty(text: String) {
    GroupedBlock {
        Text(text, Modifier.fillMaxWidth().padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DashboardFloatingNav(current: DashboardScreen, onSelect: (DashboardScreen) -> Unit) {
    val palette = LocalCourierPalette.current
    Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = if (palette.dark) 0.dp else 8.dp,
            border = if (palette.dark) BorderStroke(1.dp, palette.line) else null,
        ) {
            Row(
                Modifier.fillMaxWidth().height(66.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    Triple(DashboardScreen.HOME, "Home", Icons.Rounded.Home),
                    Triple(DashboardScreen.HISTORY, "History", Icons.Rounded.History),
                    Triple(DashboardScreen.ADDRESSES, "Addresses", Icons.Rounded.Place),
                    Triple(DashboardScreen.STATS, "Stats", Icons.Rounded.BarChart),
                    Triple(DashboardScreen.MARKET, "Pay", Icons.Rounded.Euro),
                ).forEach { (target, label, icon) ->
                    val tint = if (current == target) palette.navActive else palette.navInactive
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSelect(target) }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
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

private fun dashAveragePrice(summary: DashboardMoneySummary): String = formatDashboardMoney(
    amount = summary.averageMoney,
    currencyCode = summary.currencyCode,
    fractionDigits = summary.fractionDigits,
    mixedCurrency = summary.mixedCurrency,
)

private fun dashPerKm(summary: DashboardMoneySummary): String = formatDashboardRate(
    rate = summary.averageMoneyPerKm,
    currencyCode = summary.currencyCode,
    mixedCurrency = summary.mixedCurrency,
)

private fun dashDayAverage(day: DashboardMoneyDaySummary): String {
    val formatted = formatDashboardMoney(
        amount = day.averageMoney,
        currencyCode = day.currencyCode,
        fractionDigits = day.fractionDigits,
        mixedCurrency = day.mixedCurrency,
    )
    return if (formatted == "—" || day.mixedCurrency) formatted else "$formatted avg"
}

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

private const val HISTORY_CHUNK_SIZE = 30
private const val ADDRESS_PAGE_SIZE = 40
