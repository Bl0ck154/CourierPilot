package com.block154.courierpilot

import java.util.concurrent.ConcurrentHashMap

/** Wire contracts for market schema v2. Keep this payload deliberately privacy-minimal. */
data class MarketV2Upload(
    val schema: Int = 2,
    val installId: String,
    val offerId: String,
    val capturedAt: Long,
    val cityKey: String,
    val cityName: String?,
    val countryCode: String?,
    val platform: String,
    val currencyCode: String,
    val priceMinor: Long,
    val currencyFractionDigits: Int,
    val fullRouteDistanceM: Long,
    val routeSource: String,
    val deliveryCount: Int,
    val localHour: Int?,
    val localWeekday: Int?,
    val appVersion: String?,
    val versionCode: Int?
) {
    init {
        require(currencyCode.matches(Regex("[A-Z]{3}")))
        require(priceMinor >= 0 && currencyFractionDigits in 0..6)
        require(fullRouteDistanceM > 0 && routeSource == "FULL")
    }

    /** Explicit allow-list serialization; private offer data cannot leak accidentally. */
    fun toFields(): Map<String, Any?> = mapOf(
        "schema" to schema, "install_id" to installId, "offer_id" to offerId,
        "captured_at" to capturedAt, "city_key" to cityKey, "city_name" to cityName,
        "country_code" to countryCode, "platform" to platform, "currency_code" to currencyCode,
        "price_minor" to priceMinor, "currency_fraction_digits" to currencyFractionDigits,
        "full_route_distance_m" to fullRouteDistanceM, "route_source" to routeSource,
        "delivery_count" to deliveryCount, "local_hour" to localHour,
        "local_weekday" to localWeekday, "app_version" to appVersion, "version_code" to versionCode
    )
}

data class MarketV2Cohort(val cityKey: String, val currencyCode: String, val platform: String) {
    init { require(currencyCode.matches(Regex("[A-Z]{3}"))) }
}

data class MarketV2Profile(
    val ready: Boolean, val sampleCount: Int, val effectiveSampleCount: Double,
    val uniqueInstallations: Int, val medianNativeMoneyPerKm: Double?,
    val p15: Double?, val p35: Double?, val p65: Double?, val p85: Double?,
    val p25: Double? = null, val p75: Double? = null, val confidence: String = "NOT_READY",
    val trend: Double? = null, val windowStart: Long? = null, val generatedAt: Long? = null
)

data class MarketV2HistoryBucket(
    val period: String, val startAt: Long, val endAt: Long, val sampleCount: Int,
    val medianNativeMoneyPerKm: Double?, val p25: Double?, val p75: Double?,
    val medianPriceMinor: Long?, val medianDistanceM: Long?, val platform: String,
    val currencyCode: String, val confidence: String? = null, val uniqueInstallations: Int? = null
)

interface MarketV2Service {
    fun upload(sample: MarketV2Upload): Boolean
    fun profile(cohort: MarketV2Cohort, hour: Int? = null, weekday: Int? = null): MarketV2Profile?
    fun history(cohort: MarketV2Cohort, period: String): List<MarketV2HistoryBucket>
}

/** Offline-first sync coordinator. Dedupe is by offer_id and queued samples are retried safely. */
class MarketV2Repository(private val service: MarketV2Service) {
    private val pending = LinkedHashMap<String, MarketV2Upload>()
    private val profiles = ConcurrentHashMap<MarketV2Cohort, MarketV2Profile>()
    private val histories = ConcurrentHashMap<Pair<MarketV2Cohort, String>, List<MarketV2HistoryBucket>>()

    @Synchronized fun enqueue(sample: MarketV2Upload) { pending.putIfAbsent(sample.offerId, sample) }
    @Synchronized fun pendingCount() = pending.size
    @Synchronized fun flush(): Int {
        var sent = 0
        val iterator = pending.iterator()
        while (iterator.hasNext()) { val entry = iterator.next(); if (service.upload(entry.value)) { iterator.remove(); sent++ } }
        return sent
    }
    fun getProfile(cohort: MarketV2Cohort, hour: Int? = null, weekday: Int? = null): MarketV2Profile? {
        val fresh = service.profile(cohort, hour, weekday); if (fresh != null) profiles[cohort] = fresh
        return fresh ?: profiles[cohort]
    }
    fun getHistory(cohort: MarketV2Cohort, period: String): List<MarketV2HistoryBucket> {
        require(period in setOf("day", "week", "month"))
        val key = cohort to period; val fresh = service.history(cohort, period); histories[key] = fresh
        return fresh.ifEmpty { histories[key].orEmpty() }
    }
}
