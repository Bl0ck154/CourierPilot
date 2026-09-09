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
import androidx.compose.foundation.lazy.items
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
import com.block154.courierpilot.ui.BrandBlue
import com.block154.courierpilot.ui.BrandCyan
import com.block154.courierpilot.ui.Purple

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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { SectionHeader("Pay trends", "Your real €/km compared with recent offers") }
            item { PlatformSelector(state.platform, onPlatformSelected) }
            item { PayOverviewCard(state) }
            item { SectionHeader("History", "How pay changed over time") }
            item { HistorySelector(state.period, onPeriodSelected) }

            item { HistoryHeader("Your offers", state.personalHistory.size) }
            if (state.personalHistory.isEmpty()) {
                item { EmptyCard("Not enough personal route data yet.") }
            } else {
                items(state.personalHistory, key = { "personal:${it.label}" }) { HistoryRow(it, state.currencyCode) }
            }

            item { HistoryHeader("City", state.cityHistory.size) }
            if (state.cityHistory.isEmpty()) {
                item { EmptyCard("City comparison is still learning.") }
            } else {
                items(state.cityHistory, key = { "city:${it.label}" }) { HistoryRow(it, state.currencyCode) }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun PlatformSelector(selected: MarketPlatform, onSelect: (MarketPlatform) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MarketPlatform.entries.forEach { platform ->
            FilterChip(
                selected = selected == platform,
                onClick = { onSelect(platform) },
                label = { Text(if (platform == MarketPlatform.WOLT) "Wolt" else "Bolt") },
            )
        }
    }
}

@Composable
private fun PayOverviewCard(state: MarketScreenState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PayMetric(
                    label = "Your median",
                    value = state.personalMedian?.display() ?: "—",
                    accent = BrandBlue,
                    modifier = Modifier.weight(1f),
                )
                PayMetric(
                    label = "City median",
                    value = state.cityMedian?.display() ?: "—",
                    accent = BrandCyan,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (state.source) {
                            MarketSource.PERSONAL_AND_CITY -> "Personal + city model"
                            MarketSource.PERSONAL -> "Your offer history"
                            MarketSource.CITY -> "City comparison"
                            MarketSource.LEARNING -> "Learning your pay baseline"
                        },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                    Text(
                        "${state.sampleCount} eligible offers · ${state.confidence.displayName()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                state.trend?.let { trend ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (trend.improving) BrandBlue.copy(alpha = 0.10f) else MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                    ) {
                        Text(
                            trend.label,
                            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            color = if (trend.improving) BrandBlue else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            if (state.source == MarketSource.LEARNING) {
                Text(
                    "Learning ${state.sampleCount.coerceAtMost(state.learningTarget)} / ${state.learningTarget} before a stable personal baseline.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun PayMetric(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = accent.copy(alpha = 0.09f)) {
        Column(Modifier.padding(14.dp)) {
            Surface(shape = RoundedCornerShape(50), color = accent, modifier = Modifier.size(8.dp)) {}
            Spacer(Modifier.size(10.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HistorySelector(selected: MarketHistoryPeriod, onSelect: (MarketHistoryPeriod) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MarketHistoryPeriod.entries.forEach { period ->
            FilterChip(selected == period, { onSelect(period) }, label = { Text(period.label) })
        }
    }
}

@Composable
private fun HistoryHeader(title: String, bucketCount: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        if (bucketCount > 0) Text("$bucketCount periods", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun HistoryRow(bucket: MarketHistoryBucket, currencyCode: String) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(bucket.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(marketRate(bucket.median, currencyCode), fontWeight = FontWeight.SemiBold, color = Purple)
            }
            Text(
                "${bucket.sampleCount} offers · usual range ${marketRateRange(bucket.p25, bucket.p75, currencyCode)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Text(text, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
