package com.block154.courierpilot

import java.util.Locale

/** PII must remain solely in the UI, never in CaptureEventLog or RemoteDiagnostics. */
internal object LiveAdvisorDebugLines {
    private val postcode = Regex("""(?i)(?:\bLT\s*-?\s*)?(?<!\d)\d{5}(?!\d)""")
    private val country = Regex("""(?i)\b(lithuania|lietuva|latvia|poland|estonia)\b""")

    fun shortAddress(raw: String): String =
        country.replace(postcode.replace(raw.replace('\n', ' '), ""), "")
            .replace(Regex("""(?i),?\s*vilnius\b"""), "")
            .replace(Regex("""\s+,\s*"""), ", ")
            .trim(' ', ',')
            .take(90)

    fun wolt(pickups: List<String>, dropoffs: List<String>): List<String> = buildList {
        pickups.filter(String::isNotBlank).take(3).map(::shortAddress)
            .takeIf(List<String>::isNotEmpty)?.let { add("🍴 " + it.joinToString(" | ")) }
        dropoffs.filter(String::isNotBlank).take(3).map(::shortAddress)
            .takeIf(List<String>::isNotEmpty)?.let { add("👤 " + it.joinToString(" | ")) }
    }.take(3)

    fun bolt(
        pickupAddresses: List<String>, customerAddresses: List<String>,
        confidence: Double?, diagnostics: BoltRecoveryDiagnostics?,
        etaMinutes: Int?, etaMeters: Int?,
    ): List<String> {
        val pickup = pickupAddresses.filter(String::isNotBlank).take(3)
            .map { "🍴 " + shortAddress(it) + " · geo 0.85" }
        val customers = customerAddresses.filter(String::isNotBlank).take(3)
            .map { "👤 ≈ " + shortAddress(it) + (confidence?.let { score -> " · map " + fmt(score, 2) } ?: "") }
        val scale = diagnostics?.scaleMetersPerPixel?.let { "s " + fmt(it, 1) + " m/px" }
        val baseline = diagnostics?.anchorBaselinePx?.let { px ->
            "base " + px.toInt() + "px/" + (diagnostics.anchorBaselineMeters?.toInt()?.toString() ?: "?") + "m"
        }
        val eta = if (etaMinutes != null && etaMeters != null && etaMeters > 0)
            "eta ${etaMinutes}′≈" + fmt(etaMeters / 1000.0, 1) + " km" else null
        val geometry = listOfNotNull(scale, baseline, eta).joinToString(" · ")
        return buildList {
            if (pickup.isNotEmpty()) add(pickup.joinToString(" | "))
            if (customers.isNotEmpty()) add(customers.joinToString(" | "))
            if (geometry.isNotBlank()) add(geometry)
        }.take(3)
    }

    fun coordinate(point: RoutePoint): String = fmt(point.latitude, 4) + "," + fmt(point.longitude, 4)
    private fun fmt(value: Double, decimals: Int) = "%.${decimals}f".format(Locale.US, value)
}

internal object LiveAdvisorTerminalPolicy {
    const val VERDICT_TIMEOUT_MS = 20_000L
    fun expired(startElapsedMs: Long, nowElapsedMs: Long, isTerminal: Boolean): Boolean =
        !isTerminal && startElapsedMs > 0 && nowElapsedMs - startElapsedMs >= VERDICT_TIMEOUT_MS
}

internal data class BoltTerminalPresentation(val rateLine: String, val routeLine: String)

/** Fallback never evaluates a scored verdict or enters LiveOfferVerdictCache. */
internal object BoltTerminalPresentationPolicy {
    fun present(money: MoneyAmount?, etaMeters: Int?, etaMinutes: Int?): BoltTerminalPresentation {
        val metres = etaMeters?.takeIf { it > 0 }
        if (metres == null) return BoltTerminalPresentation("?/km", "⚠️ Route unavailable")
        val rate = money?.let { LiveAdvisorPresentation.provisionalRateLine(it, metres) } ?: "?/km"
        val distance = "≈ ${"%.1f".format(Locale.US, metres / 1000.0)} km"
        val routeLine = etaMinutes?.let { "🕒 ~$it min\n$distance" } ?: "🕒 $distance"
        return BoltTerminalPresentation(rate, routeLine)
    }
}
