package com.block154.courierpilot

import java.util.Locale

internal object LiveAdvisorPresentation {
    /** Primary live-card value. Hourly projections deliberately do not belong on the offer overlay. */
    fun rateLine(decision: OfferDecision): String {
        val code = decision.currencyCode?.takeIf(MarketCurrencyParser::isSupportedCurrencyCode)
        val moneyPerKilometer = decision.moneyPerKilometer ?: return "—/km"
        val rate = if (code == null) {
            "${"%.2f".format(Locale.US, moneyPerKilometer)}/km"
        } else {
            "${formatMoneyRate(moneyPerKilometer, code)}/km"
        }
        return "≈ $rate  ${decision.band.emoji}"
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
        return "≈ $formatted  ⏳"
    }

    fun platformDistanceLine(meters: Int): String = "📍 ${formatKm(meters)}"

    fun routeLine(walking: RouteResult?, cycling: RouteResult?): String {
        val walk = walking?.let { formatKm(it.distanceMeters) } ?: "—"
        val cycle = cycling?.let { formatKm(it.distanceMeters) } ?: "—"
        return listOf("🚶 $walk", "🚲 $cycle").joinToString("   ")
    }

    private fun formatMoneyRate(value: Double, currencyCode: String?, decimals: Int = 2): String {
        val amount = "% .${decimals}f".format(Locale.US, value).trim()
        return if (currencyCode == "EUR") "€$amount" else "${currencyCode ?: ""} $amount".trim()
    }

    private fun formatKm(meters: Int): String = "${"%.2f".format(Locale.US, meters / 1000.0)} km"
}
