package com.block154.courierpilot

import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.floor

/**
 * Local-only distributions for the distance printed by the courier platform.
 *
 * These samples MUST NOT enter market_observations, route-verified scoring,
 * city profiles or upload queues. The reported distance may omit a journey
 * to pickup, waiting, or the courier's unpaid trip back.
 */
internal object PlatformDistanceTrends {
    const val HISTORY_DAYS = 90L

    fun recentCurrency(records: List<OfferRecord>, platform: String): String? =
        records.firstOrNull { it.platform.equals(platform, ignoreCase = true) &&
            it.currencyCode.matches(Regex("[A-Z]{3}")) && it.priceCents > 0 }?.currencyCode

    fun buckets(
        records: List<OfferRecord>,
        platform: String,
        currencyCode: String,
        period: MarketHistoryPeriod,
        now: Long,
    ): List<MarketHistoryBucket> {
        val since = now - HISTORY_DAYS * 86_400_000L
        val samples = records.asSequence()
            .filter { it.capturedAt in since..now }
            .filter { it.platform.equals(platform, ignoreCase = true) }
            .filter { it.currencyCode.equals(currencyCode, ignoreCase = true) }
            .filter { it.priceCents > 0 && it.currencyFractionDigits in 0..3 }
            .filter { (it.distanceMeters ?: 0) > 0 }
            // A Wolt incremental add-on is NOT a complete offer; do not mix
            // its supplemental payout/distance into full delivery samples.
            .filterNot { it.rawText.isNotBlank() && OfferParser.parse(it.rawText).isIncrementalOffer }
            .mapNotNull { record ->
                val meters = record.distanceMeters ?: return@mapNotNull null
                val nativeMoney = MoneyAmount(
                    record.priceCents.toLong(), record.currencyCode, record.currencyFractionDigits
                ).major().toDouble()
                val rate = nativeMoney * 1000.0 / meters
                if (!rate.isFinite() || rate <= 0) null else bucketLabel(record.capturedAt, period) to rate
            }.toList()

        return samples.groupBy({ it.first }, { it.second })
            .toSortedMap(reverseOrder())
            .map { (bucket, unsorted) ->
                val rates = unsorted.sorted()
                MarketHistoryBucket(
                    label = bucket,
                    median = "%.2f".format(Locale.US, quantile(rates, .5)),
                    p25 = "%.2f".format(Locale.US, quantile(rates, .25)),
                    p75 = "%.2f".format(Locale.US, quantile(rates, .75)),
                    sampleCount = rates.size,
                )
            }
    }

    private fun bucketLabel(at: Long, period: MarketHistoryPeriod): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = at }
        return when (period) {
            MarketHistoryPeriod.DAY -> "%tF".format(Date(at))
            MarketHistoryPeriod.WEEK -> {
                calendar.firstDayOfWeek = Calendar.MONDAY
                calendar.minimalDaysInFirstWeek = 4
                "${calendar.weekYear}-W${calendar.get(Calendar.WEEK_OF_YEAR).toString().padStart(2, '0')}"
            }
            MarketHistoryPeriod.MONTH -> "%tY-%<tm".format(Date(at))
        }
    }

    private fun quantile(sorted: List<Double>, position: Double): Double {
        val index = (sorted.size - 1) * position
        val lower = floor(index).toInt()
        val fraction = index - lower
        return sorted[lower] + (sorted[minOf(lower + 1, sorted.lastIndex)] - sorted[lower]) * fraction
    }
}
