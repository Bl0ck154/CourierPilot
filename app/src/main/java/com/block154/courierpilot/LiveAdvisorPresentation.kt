package com.block154.courierpilot

import java.util.Locale
import kotlin.math.abs

internal object LiveAdvisorPresentation {
    fun profitabilityLine(decision: OfferDecision, economics: PlatformOfferEconomics): String {
        val km = decision.euroPerKilometer
            ?.let { "€${"%.2f".format(Locale.US, it)}/km" }
            ?: "€/km —"
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
        val lo = economics.euroPerHourMin
        val hi = economics.euroPerHourMax
        return when {
            lo != null && hi != null && abs(lo - hi) < 0.05 -> "€${"%.1f".format(Locale.US, lo)}/h"
            lo != null && hi != null -> "€${"%.1f".format(Locale.US, lo)}–€${"%.1f".format(Locale.US, hi)}/h"
            else -> "€/h —"
        }
    }

    private fun formatKm(meters: Int): String = "${"%.2f".format(Locale.US, meters / 1000.0)} km"
}
