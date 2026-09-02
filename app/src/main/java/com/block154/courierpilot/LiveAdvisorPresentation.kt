package com.block154.courierpilot

import java.util.Locale
import kotlin.math.abs

internal object LiveAdvisorPresentation {
    fun profitabilityLine(decision: OfferDecision, economics: PlatformOfferEconomics): String {
        val code = decision.currencyCode ?: economics.currencyCode
        val km = decision.moneyPerKilometer
            ?.let { "${formatMoneyRate(it, code)}/km" }
            ?: "${code ?: "money"}/km —"
        val hour = hourText(economics)
        return "${decision.band.emoji}  $km  •  $hour"
    }

    fun routeLine(walking: RouteResult?, cycling: RouteResult?): String {
        val walk = walking?.let { formatKm(it.distanceMeters) } ?: "—"
        val cycle = cycling?.let { formatKm(it.distanceMeters) } ?: "—"
        val average = OfferDecisionEngine.averageValhallaDistanceMeters(walking, cycling)
            ?.let { "≈ ${formatKm(it)}" }
        return listOfNotNull("🚶 $walk", "🚲 $cycle", average).joinToString("   ")
    }

    private fun hourText(economics: PlatformOfferEconomics): String {
        val lo = economics.moneyPerHourMin
        val hi = economics.moneyPerHourMax
        val code = economics.currencyCode
        return when {
            lo != null && hi != null && abs(lo - hi) < 0.05 -> "${formatMoneyRate(lo, code, 1)}/h"
            lo != null && hi != null -> "${formatMoneyRate(lo, code, 1)}–${formatMoneyRate(hi, code, 1)}/h"
            else -> "${code ?: "money"}/h —"
        }
    }

    private fun formatMoneyRate(value: Double, currencyCode: String?, decimals: Int = 2): String {
        val amount = "% .${decimals}f".format(Locale.US, value).trim()
        return if (currencyCode == "EUR") "€$amount" else "${currencyCode ?: ""} $amount".trim()
    }

    private fun formatKm(meters: Int): String = "${"%.2f".format(Locale.US, meters / 1000.0)} km"
}
