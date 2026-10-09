package com.block154.courierpilot

import java.util.Locale

internal object LiveAdvisorPresentation {
    /**
     * Primary live-card value. A verified route rate is shown as a plain number; `≈` is reserved
     * for estimates (ETA/platform-distance fallbacks) so the courier can tell them apart at a glance.
     */
    fun rateLine(decision: OfferDecision): String {
        val code = decision.currencyCode?.takeIf(MarketCurrencyParser::isSupportedCurrencyCode)
        val moneyPerKilometer = decision.moneyPerKilometer ?: return "—/km"
        val rate = if (code == null) {
            "${"%.2f".format(Locale.US, moneyPerKilometer)}/km"
        } else {
            "${formatMoneyRate(moneyPerKilometer, code)}/km"
        }
        return "$rate  ${decision.band.emoji}"
    }

    /**
     * Fast pre-route value. No verdict emoji is attached because this is deliberately an estimate;
     * the normal route-verified value replaces it when Valhalla completes.
     */
    fun provisionalRateLine(money: MoneyAmount, estimatedRouteMeters: Int): String? {
        if (estimatedRouteMeters <= 0) return null
        val major = money.major().toDouble().takeIf { it > 0.0 } ?: return null
        val rate = major / (estimatedRouteMeters / 1000.0)
        if (!rate.isFinite() || rate <= 0.0) return null
        val code = money.currencyCode.takeIf(MarketCurrencyParser::isSupportedCurrencyCode)
        val formatted = if (code == null) {
            "${"%.2f".format(Locale.US, rate)}/km"
        } else {
            "${formatMoneyRate(rate, code)}/km"
        }
        return "≈$formatted  ⏳"
    }

    fun platformDistanceLine(meters: Int): String = "📍 ${formatKm(meters)}"

    /** Two short lines: the card shows them right-aligned next to the rate. */
    fun routeLine(walking: RouteResult?, cycling: RouteResult?): String {
        val walk = walking?.let { formatKm(it.distanceMeters) } ?: "—"
        val cycle = cycling?.let { formatKm(it.distanceMeters) } ?: "—"
        return "🚶 $walk\n🚲 $cycle"
    }

    private fun formatMoneyRate(value: Double, currencyCode: String?, decimals: Int = 2): String {
        val amount = "% .${decimals}f".format(Locale.US, value).trim()
        return if (currencyCode == "EUR") "€$amount" else "${currencyCode ?: ""} $amount".trim()
    }

    private fun formatKm(meters: Int): String = "${"%.2f".format(Locale.US, meters / 1000.0)} km"
}

/** The live-card rate split into the big number, the small unit and the trailing verdict emoji. */
internal data class LiveAdvisorRateParts(val value: String, val unit: String, val emoji: String)

/**
 * Visual weight follows money/km: the best offers glow in saturated gold, worse ones fade towards
 * grey until a terrible offer is barely visible. The emoji is desaturated and faded the same way.
 */
internal data class LiveAdvisorRateStyle(
    val color: Int,
    val glowColor: Int,
    val glowRadiusDp: Float,
    val emojiSaturation: Float,
    val emojiAlpha: Float,
)

internal object LiveAdvisorRateStylePolicy {
    fun split(line: String): LiveAdvisorRateParts {
        val trimmed = line.trim()
        val lastSpace = trimmed.lastIndexOf(' ')
        val tail = if (lastSpace >= 0) trimmed.substring(lastSpace + 1) else ""
        val hasEmojiTail = tail.isNotEmpty() && tail.none { it.isLetterOrDigit() || it == '/' || it == '€' }
        val rate = if (hasEmojiTail) trimmed.substring(0, lastSpace).trim() else trimmed
        val emoji = if (hasEmojiTail) tail else ""
        val unitIndex = rate.lastIndexOf("/km")
        return if (unitIndex > 0) {
            LiveAdvisorRateParts(rate.substring(0, unitIndex), rate.substring(unitIndex), emoji)
        } else {
            LiveAdvisorRateParts(rate, "", emoji)
        }
    }

    /** The number must never ellipsize (0.16.0 showed `€1.4…`): longer values get a smaller size. */
    fun valueTextSp(value: String): Float = when {
        value.length <= 6 -> 30f
        value.length <= 8 -> 26f
        else -> 22f
    }

    fun style(band: OfferDecisionBand, estimate: Boolean): LiveAdvisorRateStyle = when {
        estimate -> LiveAdvisorRateStyle(argb(255, 203, 213, 225), 0, 0f, 0.6f, 0.8f)
        else -> when (band) {
            OfferDecisionBand.FIRE -> LiveAdvisorRateStyle(argb(255, 255, 214, 10), argb(140, 255, 214, 10), 7f, 1.25f, 1f)
            OfferDecisionBand.GOOD -> LiveAdvisorRateStyle(argb(255, 242, 197, 61), argb(72, 242, 197, 61), 4f, 0.9f, 0.95f)
            OfferDecisionBand.OK -> LiveAdvisorRateStyle(argb(255, 189, 177, 138), 0, 0f, 0.45f, 0.8f)
            OfferDecisionBand.BAD -> LiveAdvisorRateStyle(argb(255, 126, 130, 138), 0, 0f, 0.15f, 0.55f)
            OfferDecisionBand.TERRIBLE -> LiveAdvisorRateStyle(argb(255, 75, 81, 92), 0, 0f, 0f, 0.32f)
            OfferDecisionBand.UNKNOWN -> LiveAdvisorRateStyle(argb(255, 203, 213, 225), 0, 0f, 0.6f, 0.8f)
        }
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}
