package com.block154.courierpilot

import com.block154.courierpilot.ui.RateGrade
import java.util.Locale

/** Money per route kilometre for one History/Home row, graded like the live card. */
internal data class OfferRowRate(
    val value: String,
    val unit: String,
    val perKm: Double,
    val grade: RateGrade,
)

internal object OfferRowRatePolicy {
    fun rate(record: OfferRecord): OfferRowRate? {
        val meters = record.effectiveRouteDistanceMeters?.takeIf { it > 0 } ?: return null
        val money = runCatching {
            MoneyAmount(
                amountMinor = record.priceCents.toLong(),
                currencyCode = record.currencyCode,
                fractionDigits = record.currencyFractionDigits,
            ).major().toDouble()
        }.getOrNull()?.takeIf { it > 0.0 } ?: return null
        val perKm = money / (meters / 1000.0)
        return OfferRowRate(
            value = formatValue(perKm, record.currencyCode),
            unit = "/km",
            perKm = perKm,
            grade = gradeFor(perKm, record.currencyCode),
        )
    }

    /**
     * History has no live market snapshot, so it uses the same currency-specific cold-start bands
     * as the live card. Currencies without a calibrated baseline stay neutral instead of guessed.
     */
    fun gradeFor(perKm: Double?, currencyCode: String?): RateGrade {
        val value = perKm ?: return RateGrade.UNKNOWN
        val thresholds = LiveOfferColdStartThresholds.forCurrency(currencyCode) ?: return RateGrade.UNKNOWN
        return when (OfferDecisionEngine.bandFor(value, thresholds)) {
            OfferDecisionBand.FIRE -> RateGrade.FIRE
            OfferDecisionBand.GOOD -> RateGrade.GOOD
            OfferDecisionBand.OK -> RateGrade.OK
            OfferDecisionBand.BAD -> RateGrade.BAD
            OfferDecisionBand.TERRIBLE -> RateGrade.TERRIBLE
            OfferDecisionBand.UNKNOWN -> RateGrade.UNKNOWN
        }
    }

    fun formatValue(perKm: Double, currencyCode: String?): String {
        val number = String.format(Locale.US, "%.2f", perKm)
        return if (currencyCode.equals("EUR", ignoreCase = true) || currencyCode.isNullOrBlank()) "€$number" else "$currencyCode $number"
    }
}
