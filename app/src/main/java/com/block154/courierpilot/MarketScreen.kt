package com.block154.courierpilot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

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
    onPlatformSelected: (MarketPlatform) -> Unit = {},
    onPeriodSelected: (MarketHistoryPeriod) -> Unit = {},
    onRetry: () -> Unit = {},
) {
    when {
        state.loading -> Column(Modifier.fillMaxSize(), Arrangement.Center) { CircularProgressIndicator(Modifier.padding(24.dp)) }
        state.offline -> OfflineMarketState(onRetry)
        else -> LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Market / Pay trends", style = MaterialTheme.typography.headlineMedium) }
            item { PlatformSelector(state.platform, onPlatformSelected) }
            item { OverviewCard(state) }
            item { HistorySelector(state.period, onPeriodSelected) }
            item { Text("Your history", style = MaterialTheme.typography.titleMedium) }
            if (state.personalHistory.isEmpty()) item { Text("No personal history yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else items(state.personalHistory) { HistoryRow(it, state.currencyCode) }
            item { Text("City history", style = MaterialTheme.typography.titleMedium) }
            if (state.cityHistory.isEmpty()) item { Text("No collective city history yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else items(state.cityHistory) { HistoryRow(it, state.currencyCode) }
        }
    }
}

@Composable private fun PlatformSelector(selected: MarketPlatform, onSelect: (MarketPlatform) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { MarketPlatform.entries.forEach { platform ->
        FilterChip(selected == platform, { onSelect(platform) }, label = { Text(platform.name) })
    } }
}

@Composable private fun OverviewCard(state: MarketScreenState) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${state.platform.name} market", style = MaterialTheme.typography.titleLarge)
        MedianLine("Personal median", state.personalMedian)
        MedianLine("City median", state.cityMedian)
        Text("Source: ${state.source.displayName()} · Confidence: ${state.confidence.displayName()}")
        Text("${state.sampleCount} eligible offers${state.percentile?.let { " · ${it}th percentile" } ?: ""}")
        if (state.source == MarketSource.LEARNING) Text("Learning ${state.sampleCount.coerceAtMost(state.learningTarget)} / ${state.learningTarget}")
        state.trend?.let { Text("7d trend ${it.label}", color = if (it.improving) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
    } }
}

@Composable private fun MedianLine(label: String, median: MarketMedian?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Text(median?.let { "${it.value} ${it.currencyCode}/km" } ?: "—") }
}
@Composable private fun HistorySelector(selected: MarketHistoryPeriod, onSelect: (MarketHistoryPeriod) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { MarketHistoryPeriod.entries.forEach { FilterChip(selected == it, { onSelect(it) }, label = { Text(it.label) }) } }
}
@Composable private fun HistoryRow(bucket: MarketHistoryBucket, currencyCode: String) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(bucket.label); Text("${bucket.sampleCount} offers") }
        Text("Median ${bucket.median} $currencyCode/km · P25–P75 ${bucket.p25}–${bucket.p75}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}
@Composable private fun OfflineMarketState(onRetry: () -> Unit) { Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center) { Text("Market data is offline"); Spacer(Modifier.height(12.dp)); Button(onRetry) { Text("Retry") } } }

private fun MarketSource.displayName() = when (this) { MarketSource.PERSONAL_AND_CITY -> "Personal + City"; else -> name.lowercase().replaceFirstChar { it.uppercase() } }
private fun MarketUiConfidence.displayName() = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

@Preview(showBackground = true)
@Composable private fun MarketScreenPreview() { MarketScreen(MarketScreenState(personalMedian = MarketMedian("1.24", "EUR"), cityMedian = MarketMedian("1.31", "EUR"), percentile = 68, rating = "GOOD", source = MarketSource.PERSONAL_AND_CITY, confidence = MarketUiConfidence.MEDIUM, sampleCount = 14, trend = MarketUiTrend(8.4, true), personalHistory = listOf(MarketHistoryBucket("Mon", "1.30", "1.05", "1.56", 8)), cityHistory = listOf(MarketHistoryBucket("Mon", "1.34", "1.10", "1.61", 42)))) }
