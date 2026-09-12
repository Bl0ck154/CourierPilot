package com.block154.courierpilot

import android.content.Context
import kotlin.math.ceil

internal data class ArrivalEtaWindow(
    val minMinutes: Int,
    val maxMinutes: Int,
    val source: String,
)

/** ETA-based fallback for access-code reminders when background location is unavailable. */
internal object ArrivalAccessHintTimingPolicy {
    private val rangeRegex = Regex("(?i)\\b(\\d{1,3})\\s*[-–—]\\s*(\\d{1,3})\\s*min(?:ute)?s?\\b")
    private val singleRegex = Regex("(?i)(?:^|\\s)(~?\\s*\\d{1,3})\\s*min(?:ute)?s?\\b")

    const val LEAD_TIME_MS = 90_000L
    const val MIN_DELAY_MS = 30_000L
    const val DEFAULT_DELAY_MS = 6L * 60L * 1000L

    fun fromScreen(text: String): ArrivalEtaWindow? {
        val lines = text.lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter(String::isNotEmpty)
            .toList()

        for (line in lines) {
            if (isNonArrivalEtaLine(line)) continue
            rangeRegex.find(line)?.let { match ->
                val min = match.groupValues[1].toIntOrNull()
                val max = match.groupValues[2].toIntOrNull()
                if (min != null && max != null && min in 1..240 && max in min..240) {
                    return ArrivalEtaWindow(min, max, "delivery-screen-range")
                }
            }
        }
        for (line in lines) {
            if (isNonArrivalEtaLine(line)) continue
            singleRegex.find(line)?.groupValues?.getOrNull(1)
                ?.replace("~", "")
                ?.trim()
                ?.toIntOrNull()
                ?.takeIf { it in 1..240 }
                ?.let { return ArrivalEtaWindow(it, it, "delivery-screen-single") }
        }
        return null
    }

    fun fromOffer(record: OfferRecord, now: Long = System.currentTimeMillis()): ArrivalEtaWindow? {
        val originalMin = record.estimatedMinutesMin ?: record.estimatedMinutesMax ?: return null
        val originalMax = record.estimatedMinutesMax ?: record.estimatedMinutesMin ?: return null
        if (originalMin !in 1..240 || originalMax !in originalMin..240) return null
        val elapsedMs = (now - record.capturedAt).coerceAtLeast(0L)
        if (elapsedMs > MAX_OFFER_ETA_AGE_MS) return null
        val elapsedMinutes = ceil(elapsedMs / 60_000.0).toInt()
        val remainingMin = (originalMin - elapsedMinutes).coerceAtLeast(1)
        val remainingMax = (originalMax - elapsedMinutes).coerceAtLeast(remainingMin)
        return ArrivalEtaWindow(remainingMin, remainingMax, "active-offer")
    }

    fun notificationDelayMs(eta: ArrivalEtaWindow?): Long {
        if (eta == null) return DEFAULT_DELAY_MS
        return (eta.minMinutes * 60_000L - LEAD_TIME_MS).coerceAtLeast(MIN_DELAY_MS)
    }

    private fun isNonArrivalEtaLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("extra") ||
            lower.contains("ready in") ||
            lower.contains("ready for") ||
            lower.contains("preparing") ||
            lower.contains("pickup")
    }

    private const val MAX_OFFER_ETA_AGE_MS = 3L * 60L * 60L * 1000L
}

internal object ArrivalAccessHintEtaResolver {
    fun resolve(
        context: Context,
        packageName: String,
        screenText: String,
        now: Long = System.currentTimeMillis(),
    ): ArrivalEtaWindow? {
        ArrivalAccessHintTimingPolicy.fromScreen(screenText)?.let { return it }
        val task = DeliveryLifecycleTracking.currentTask(context, packageName) ?: return null
        val record = OfferDatabase.get(context).findById(task.offerId) ?: return null
        return ArrivalAccessHintTimingPolicy.fromOffer(record, now)
    }
}
