package com.block154.courierpilot

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.block154.courierpilot.ui.FilterChipD
import com.block154.courierpilot.ui.GroupedBlock
import com.block154.courierpilot.ui.GroupedRow
import com.block154.courierpilot.ui.LocalCourierPalette
import com.block154.courierpilot.ui.RateNumberFamily
import com.block154.courierpilot.ui.RateText
import com.block154.courierpilot.ui.VerdictEmoji

enum class MarketPlatform { WOLT, BOLT }
enum class MarketHistoryPeriod(val label: String) { DAY("Day"), WEEK("Week"), MONTH("Month") }
enum class MarketUiConfidence { NOT_READY, LOW, MEDIUM, HIGH }
enum class MarketSource { LEARNING, PERSONAL, CITY, PERSONAL_AND_CITY }

data class MarketMedian(val value: String, val currencyCode: String)
data class MarketUiTrend(val percent: Double, val improving: Boolean) {
    val label: String get() = (if (improving) "↑" else "↓") + " " + "%+.1f".format(percent) + "%"
}
data class MarketHistoryBucket(
    val label: String,
    val median: String,
    val p25: String,
    val p75: String,
    val sampleCount: Int,
)
data class MarketScreenState(
    val platform: MarketPlatform = MarketPlatform.WOLT,
    val currencyCode: String = "EUR",
    val personalMedian: MarketMedian? = null,
    val cityMedian: MarketMedian? = null,
    val percentile: Int? = null,
    val rating: String? = null,
    val source: MarketSource = MarketSource.LEARNING,
    val confidence: MarketUiConfidence = MarketUiConfidence.NOT_READY,
    val sampleCount: Int = 0,
    val learningTarget: Int = 5,
    val trend: MarketUiTrend? = null,
    val period: MarketHistoryPeriod = MarketHistoryPeriod.WEEK,
    val personalHistory: List<MarketHistoryBucket> = emptyList(),
    val cityHistory: List<MarketHistoryBucket> = emptyList(),
    val loading: Boolean = false,
    val offline: Boolean = false,
)

@Composable
fun MarketScreen(
    state: MarketScreenState,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onPlatformSelected: (MarketPlatform) -> Unit = {},
    onPeriodSelected: (MarketHistoryPeriod) -> Unit = {},
    onRetry: () -> Unit = {},
) {
    when {
        state.loading -> Column(
            Modifier.fillMaxSize().padding(contentPadding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { CircularProgressIndicator() }

        state.offline -> OfflineMarketState(contentPadding, onRetry)

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item {
                Column(Modifier.padding(start = 4.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Pay", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Your €/km compared with the city", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
            item {
                Row(Modifier.padding(top = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MarketPlatform.entries.forEach { platform ->
                        FilterChipD(
                            if (platform == MarketPlatform.WOLT) "Wolt" else "Bolt",
                            selected = state.platform == platform,
                        ) { onPlatformSelected(platform) }
                    }
                }
            }
            item { PayOverviewCard(state) }

            item {
                Row(Modifier.padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("History", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            item {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MarketHistoryPeriod.entries.forEach { period ->
                        FilterChipD(period.label, selected = state.period == period) { onPeriodSelected(period) }
                    }
                }
            }

            item { HistoryLabel("Your offers") }
            if (state.personalHistory.isEmpty()) {
                item { EmptyCard("Not enough personal route data yet.") }
            } else {
                itemsIndexed(state.personalHistory, key = { _, bucket -> "personal:${bucket.label}" }) { index, bucket ->
                    HistoryRow(bucket, state.currencyCode, index, state.personalHistory.size)
                }
            }

            item { HistoryLabel("City") }
            if (state.cityHistory.isEmpty()) {
                item { EmptyCard("City comparison is still learning.") }
            } else {
                itemsIndexed(state.cityHistory, key = { _, bucket -> "city:${bucket.label}" }) { index, bucket ->
                    HistoryRow(bucket, state.currencyCode, index, state.cityHistory.size)
                }
            }
        }
    }
}

@Composable
private fun HistoryLabel(text: String) {
    Text(
        text.uppercase(),
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.6.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PayOverviewCard(state: MarketScreenState) {
    val palette = LocalCourierPalette.current
    GroupedBlock {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PayMedian("You", state.personalMedian, Modifier.weight(1f), palette.miniStatBg)
                PayMedian("City", state.cityMedian, Modifier.weight(1f), palette.miniStatBg)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        when (state.source) {
                            MarketSource.PERSONAL_AND_CITY -> "Personal + city model"
                            MarketSource.PERSONAL -> "Your offer history"
                            MarketSource.CITY -> "City comparison"
                            MarketSource.LEARNING -> "Learning your pay baseline"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    Text(
                        "${state.sampleCount} eligible offers · ${state.confidence.displayName()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.5.sp,
                    )
                }
                state.trend?.let { trend ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (trend.improving) palette.onlineBg else palette.offlineBg,
                    ) {
                        Text(
                            trend.label,
                            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            color = if (trend.improving) palette.onlineText else palette.offlineText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            if (state.source == MarketSource.LEARNING) {
                Text(
                    "Learning ${state.sampleCount.coerceAtMost(state.learningTarget)} / ${state.learningTarget} before a stable personal baseline.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.5.sp,
                )
            }
        }
    }
}

@Composable
private fun PayMedian(label: String, median: MarketMedian?, modifier: Modifier, background: Color) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = background) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("$label · median", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 1)
            val value = median?.value?.toRate()
            if (median == null || value == null) {
                Text("—", fontFamily = RateNumberFamily, fontSize = 32.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val grade = OfferRowRatePolicy.gradeFor(value, median.currencyCode)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RateText(OfferRowRatePolicy.formatValue(value, median.currencyCode), "/km", grade, valueSize = 32.sp, unitSize = 13.sp)
                    Spacer(Modifier.size(6.dp))
                    VerdictEmoji(grade, size = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(bucket: MarketHistoryBucket, currencyCode: String, index: Int, count: Int) {
    GroupedRow(index = index, count = count) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(bucket.label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                "${bucket.sampleCount} offers · usual ${marketRateRange(bucket.p25, bucket.p75, currencyCode)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp,
                maxLines = 1,
            )
        }
        val value = bucket.median.toRate()
        if (value == null) {
            Text("—", fontFamily = RateNumberFamily, fontSize = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val grade = OfferRowRatePolicy.gradeFor(value, currencyCode)
            RateText(OfferRowRatePolicy.formatValue(value, currencyCode), "/km", grade)
            VerdictEmoji(grade)
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    GroupedBlock {
        Text(text, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun OfflineMarketState(contentPadding: PaddingValues, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(contentPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Pay trends are offline")
        Spacer(Modifier.size(12.dp))
        Button(onRetry) { Text("Retry") }
    }
}

/** Medians are pre-formatted with the device locale, so `1,24` must parse as well as `1.24`. */
private fun String.toRate(): Double? = replace(',', '.').trim().toDoubleOrNull()

private fun MarketMedian.display(): String = marketRate(value, currencyCode)

private fun marketRate(value: String, currencyCode: String): String =
    if (currencyCode.equals("EUR", ignoreCase = true)) "€$value/km" else "$value $currencyCode/km"

private fun marketRateRange(low: String, high: String, currencyCode: String): String =
    if (currencyCode.equals("EUR", ignoreCase = true)) "€$low–€$high/km" else "$low–$high $currencyCode/km"
private fun MarketUiConfidence.displayName() = when (this) {
    MarketUiConfidence.NOT_READY -> "learning"
    MarketUiConfidence.LOW -> "low confidence"
    MarketUiConfidence.MEDIUM -> "medium confidence"
    MarketUiConfidence.HIGH -> "high confidence"
}

@Preview(showBackground = true)
@Composable
private fun MarketScreenPreview() {
    MarketScreen(
        MarketScreenState(
            personalMedian = MarketMedian("1.24", "EUR"),
            cityMedian = MarketMedian("1.31", "EUR"),
            percentile = 68,
            rating = "GOOD",
            source = MarketSource.PERSONAL_AND_CITY,
            confidence = MarketUiConfidence.MEDIUM,
            sampleCount = 14,
            trend = MarketUiTrend(8.4, true),
            personalHistory = listOf(MarketHistoryBucket("Mon", "1.30", "1.05", "1.56", 8)),
            cityHistory = listOf(MarketHistoryBucket("Mon", "1.34", "1.10", "1.61", 42)),
        )
    )
}
