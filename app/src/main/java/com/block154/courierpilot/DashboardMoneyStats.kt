package com.block154.courierpilot

import java.util.Locale
import kotlin.math.pow

/**
 * Currency-aware aggregates for dashboard/history summaries.
 *
 * Raw minor units from different currencies (or currencies with different fraction digits) must
 * never be averaged together. A mixed-currency period still reports offer counts and route distance,
 * but monetary averages are deliberately withheld instead of showing a plausible-looking lie.
 */
internal data class DashboardMoneySummary(
    val count: Int,
    val averageMoney: Double?,
    val averageDistanceMeters: Double?,
    val averageMoneyPerKm: Double?,
    val currencyCode: String?,
    val fractionDigits: Int?,
    val mixedCurrency: Boolean,
)

internal data class DashboardMoneyDaySummary(
    val day: String,
    val count: Int,
    val woltCount: Int,
    val boltCount: Int,
    val averageMoney: Double?,
    val averageMoneyPerKm: Double?,
    val currencyCode: String?,
    val fractionDigits: Int?,
    val mixedCurrency: Boolean,
)

internal object DashboardMoneyStats {
    // Keep this expression aligned with OfferDatabase's trusted effective-distance policy. It uses
    // the same public policy constants so the dashboard never accepts an implausibly long calculated
    // route merely because it is aggregating historical rows.
    private val trustedEffectiveDistanceSql = """
        CASE
            WHEN market_route_distance_meters > 0 AND (
                distance_meters IS NULL OR distance_meters < ${OfferRouteDistancePolicy.MIN_COMPARABLE_PLATFORM_METERS} OR
                market_route_distance_meters <= distance_meters +
                    CASE
                        WHEN distance_meters * ${OfferRouteDistancePolicy.RELATIVE_ALLOWANCE} > ${OfferRouteDistancePolicy.ABSOLUTE_ALLOWANCE_METERS}
                        THEN distance_meters * ${OfferRouteDistancePolicy.RELATIVE_ALLOWANCE}
                        ELSE ${OfferRouteDistancePolicy.ABSOLUTE_ALLOWANCE_METERS}
                    END
            ) THEN market_route_distance_meters
            ELSE NULLIF(distance_meters, 0)
        END
    """.trimIndent()

    fun summarySince(database: OfferDatabase, since: Long, platform: String? = null): DashboardMoneySummary {
        val where = if (platform == null) "captured_at >= ?" else "captured_at >= ? AND platform = ?"
        val args = if (platform == null) arrayOf(since.toString()) else arrayOf(since.toString(), platform)
        val sql = """
            SELECT COUNT(*) AS count,
                   MIN(currency_code) AS min_currency,
                   MAX(currency_code) AS max_currency,
                   MIN(currency_fraction_digits) AS min_digits,
                   MAX(currency_fraction_digits) AS max_digits,
                   AVG(price_cents) AS avg_price_minor,
                   AVG($trustedEffectiveDistanceSql) AS avg_distance,
                   AVG(CASE
                       WHEN ($trustedEffectiveDistanceSql) IS NOT NULL
                       THEN price_cents * 1000.0 / ($trustedEffectiveDistanceSql)
                   END) AS avg_minor_per_km
            FROM offers
            WHERE $where
        """.trimIndent()

        database.readableDatabase.rawQuery(sql, args).use { cursor ->
            cursor.moveToFirst()
            val count = cursor.getInt(0)
            val minCurrency = cursor.getStringOrNull(1)
            val maxCurrency = cursor.getStringOrNull(2)
            val minDigits = cursor.getIntOrNull(3)
            val maxDigits = cursor.getIntOrNull(4)
            val homogeneous = count > 0 &&
                minCurrency != null && minCurrency == maxCurrency &&
                minDigits != null && minDigits == maxDigits
            val digits = minDigits?.takeIf { homogeneous }
            val divisor = digits?.let { 10.0.pow(it) }
            return DashboardMoneySummary(
                count = count,
                averageMoney = if (homogeneous && !cursor.isNull(5)) cursor.getDouble(5) / divisor!! else null,
                averageDistanceMeters = if (cursor.isNull(6)) null else cursor.getDouble(6),
                averageMoneyPerKm = if (homogeneous && !cursor.isNull(7)) cursor.getDouble(7) / divisor!! else null,
                currencyCode = minCurrency?.takeIf { homogeneous },
                fractionDigits = digits,
                mixedCurrency = count > 0 && !homogeneous,
            )
        }
    }

    fun dailyStats(database: OfferDatabase, limit: Int = 30): List<DashboardMoneyDaySummary> {
        val out = mutableListOf<DashboardMoneyDaySummary>()
        val sql = """
            SELECT strftime('%Y-%m-%d', captured_at / 1000, 'unixepoch', 'localtime') AS day,
                   COUNT(*) AS count,
                   SUM(CASE WHEN platform = 'Wolt' THEN 1 ELSE 0 END) AS wolt_count,
                   SUM(CASE WHEN platform = 'Bolt' THEN 1 ELSE 0 END) AS bolt_count,
                   MIN(currency_code) AS min_currency,
                   MAX(currency_code) AS max_currency,
                   MIN(currency_fraction_digits) AS min_digits,
                   MAX(currency_fraction_digits) AS max_digits,
                   AVG(price_cents) AS avg_price_minor,
                   AVG(CASE
                       WHEN ($trustedEffectiveDistanceSql) IS NOT NULL
                       THEN price_cents * 1000.0 / ($trustedEffectiveDistanceSql)
                   END) AS avg_minor_per_km
            FROM offers
            GROUP BY day
            ORDER BY day DESC
            LIMIT ?
        """.trimIndent()

        database.readableDatabase.rawQuery(sql, arrayOf(limit.coerceIn(1, 365).toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val count = cursor.getInt(1)
                val minCurrency = cursor.getStringOrNull(4)
                val maxCurrency = cursor.getStringOrNull(5)
                val minDigits = cursor.getIntOrNull(6)
                val maxDigits = cursor.getIntOrNull(7)
                val homogeneous = count > 0 &&
                    minCurrency != null && minCurrency == maxCurrency &&
                    minDigits != null && minDigits == maxDigits
                val digits = minDigits?.takeIf { homogeneous }
                val divisor = digits?.let { 10.0.pow(it) }
                out += DashboardMoneyDaySummary(
                    day = cursor.getString(0),
                    count = count,
                    woltCount = cursor.getInt(2),
                    boltCount = cursor.getInt(3),
                    averageMoney = if (homogeneous && !cursor.isNull(8)) cursor.getDouble(8) / divisor!! else null,
                    averageMoneyPerKm = if (homogeneous && !cursor.isNull(9)) cursor.getDouble(9) / divisor!! else null,
                    currencyCode = minCurrency?.takeIf { homogeneous },
                    fractionDigits = digits,
                    mixedCurrency = count > 0 && !homogeneous,
                )
            }
        }
        return out
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun android.database.Cursor.getIntOrNull(index: Int): Int? =
        if (isNull(index)) null else getInt(index)
}

internal fun formatDashboardMoney(
    amount: Double?,
    currencyCode: String?,
    fractionDigits: Int?,
    mixedCurrency: Boolean = false,
): String {
    if (mixedCurrency) return "Mixed currencies"
    val value = amount ?: return "—"
    val code = currencyCode?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: return "—"
    val digits = fractionDigits?.coerceIn(0, 6) ?: return "—"
    val number = String.format(Locale.US, "%.${digits}f", value)
    return if (code == "EUR") "€$number" else "$code $number"
}

internal fun formatDashboardRate(
    rate: Double?,
    currencyCode: String?,
    mixedCurrency: Boolean = false,
): String {
    if (mixedCurrency) return "Mixed currencies"
    val value = rate ?: return "—"
    val code = currencyCode?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: return "—"
    val number = String.format(Locale.US, "%.2f", value)
    return if (code == "EUR") "€$number/km" else "$code $number/km"
}

internal fun dashboardRateLabel(currencyCode: String?, mixedCurrency: Boolean): String = when {
    mixedCurrency -> "Avg pay/km"
    currencyCode == "EUR" -> "Avg €/km"
    !currencyCode.isNullOrBlank() -> "Avg $currencyCode/km"
    else -> "Avg pay/km"
}

internal fun formatDashboardOfferMoney(record: OfferRecord): String = formatDashboardMoney(
    amount = MoneyAmount(
        amountMinor = record.priceCents.toLong(),
        currencyCode = record.currencyCode,
        fractionDigits = record.currencyFractionDigits,
    ).major().toDouble(),
    currencyCode = record.currencyCode,
    fractionDigits = record.currencyFractionDigits,
)
