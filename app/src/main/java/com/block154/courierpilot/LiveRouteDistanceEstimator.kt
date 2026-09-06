package com.block154.courierpilot

import android.content.Context
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

internal data class LiveRouteDistanceEstimate(
    val distanceMeters: Int,
    val source: String,
    val sampleCount: Int,
)

/**
 * Instant local estimate used only while the real Valhalla route is still being prepared.
 *
 * Wolt/Bolt already expose a platform distance on many offer cards. CourierPilot learns the user's
 * recent relationship between that platform distance and the later full Valhalla distance, then
 * applies the learned median ratio without network/geocoder work. The real route always replaces
 * this estimate as soon as it arrives.
 */
internal object LiveRouteDistanceEstimator {
    private const val PREFS = "courierpilot_live_route_distance_estimator"
    private const val HISTORY_DAYS = 45L
    private const val HISTORY_LIMIT = 80
    internal const val MIN_HISTORY_SAMPLES = 3
    private const val MIN_RATIO = 0.20
    private const val MAX_RATIO = 3.00
    private const val UPDATE_WINDOW = 20

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CourierPilot-RouteEstimate").apply { isDaemon = true }
    }

    fun resume(context: Context) {
        val app = context.applicationContext
        executor.execute {
            refreshPlatform(app, "Wolt")
            refreshPlatform(app, "Bolt")
        }
    }

    fun estimate(context: Context, platform: String, platformDistanceMeters: Int?): LiveRouteDistanceEstimate? {
        val platformMeters = platformDistanceMeters?.takeIf { it > 0 } ?: return null
        val normalized = normalizePlatform(platform) ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = prefs.getInt(countKey(normalized), 0)
        val learnedFactor = prefs.getFloat(factorKey(normalized), 1f).toDouble()
            .takeIf { it in MIN_RATIO..MAX_RATIO }
        val factor = if (count >= MIN_HISTORY_SAMPLES && learnedFactor != null) learnedFactor else 1.0
        return LiveRouteDistanceEstimate(
            distanceMeters = (platformMeters * factor).roundToInt().coerceAtLeast(1),
            source = if (count >= MIN_HISTORY_SAMPLES && learnedFactor != null) "local_history" else "platform_distance",
            sampleCount = count,
        )
    }

    /** Keep the hot estimate current immediately; the DB-backed median is rebuilt on next startup. */
    fun record(context: Context, platform: String, platformDistanceMeters: Int?, routeDistanceMeters: Int) {
        val platformMeters = platformDistanceMeters?.takeIf { it > 0 } ?: return
        if (routeDistanceMeters <= 0) return
        val normalized = normalizePlatform(platform) ?: return
        val ratio = routeDistanceMeters.toDouble() / platformMeters.toDouble()
        if (ratio !in MIN_RATIO..MAX_RATIO) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldCount = prefs.getInt(countKey(normalized), 0)
        val oldFactor = prefs.getFloat(factorKey(normalized), ratio.toFloat()).toDouble()
        val weight = oldCount.coerceIn(0, UPDATE_WINDOW)
        val updated = if (weight == 0) ratio else (oldFactor * weight + ratio) / (weight + 1)
        prefs.edit()
            .putFloat(factorKey(normalized), updated.toFloat())
            .putInt(countKey(normalized), (oldCount + 1).coerceAtMost(10_000))
            .apply()
    }

    internal fun historicalFactor(ratios: List<Double>): Double? {
        val sorted = ratios.filter { it in MIN_RATIO..MAX_RATIO }.sorted()
        if (sorted.isEmpty()) return null
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun refreshPlatform(context: Context, platform: String) {
        val ratios = runCatching {
            OfferDatabase.get(context).recentRouteDistanceRatios(
                platform = platform,
                since = System.currentTimeMillis() - HISTORY_DAYS * 86_400_000L,
                limit = HISTORY_LIMIT,
            )
        }.getOrDefault(emptyList())
        val factor = historicalFactor(ratios) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(factorKey(platform), factor.toFloat())
            .putInt(countKey(platform), ratios.size)
            .apply()
    }

    private fun normalizePlatform(platform: String): String? = when {
        platform.equals("Wolt", ignoreCase = true) -> "Wolt"
        platform.equals("Bolt", ignoreCase = true) -> "Bolt"
        else -> null
    }

    private fun factorKey(platform: String) = "${platform.lowercase(Locale.ROOT)}_factor"
    private fun countKey(platform: String) = "${platform.lowercase(Locale.ROOT)}_count"
}
