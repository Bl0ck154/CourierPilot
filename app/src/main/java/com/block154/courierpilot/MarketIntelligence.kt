package com.block154.courierpilot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal data class MarketTrend(
    val percent: Double,
    val direction: String,
)

internal data class MarketProfile(
    val cityKey: String,
    val cityName: String,
    val platform: String,
    val source: String,
    val ready: Boolean,
    val sampleCount: Int,
    val uniqueInstallations: Int,
    val medianEurPerKm: Double?,
    val confidence: String,
    val thresholds: OfferDecisionThresholds?,
    val trend: MarketTrend?,
    val fetchedAt: Long,
    val currencyCode: String = "EUR",
)

internal data class MarketIntelligenceStatus(
    val sharingEnabled: Boolean,
    val city: MarketCity?,
    val queued: Int,
    val lastUploadAt: Long,
    val lastError: String,
    val localWoltProfile: LocalMarketProfile?,
    val localBoltProfile: LocalMarketProfile?,
    val woltProfile: MarketProfile?,
    val boltProfile: MarketProfile?,
)

/**
 * Privacy-minimal city market intelligence.
 *
 * Uploads contain only city/country, platform, offer price, real Valhalla route distance, local
 * hour/weekday and a pseudonymous CourierPilot install id. No address, customer/merchant name,
 * screenshot, OCR text or exact coordinate enters this queue.
 *
 * Market profiles are useful even when sharing is disabled: the app can download aggregate city
 * bands and use the old fixed thresholds whenever the server has too little data or is unavailable.
 */
internal object MarketIntelligence {
    private const val OFFERS_ENDPOINT = "https://wolt-api.zivkr.pp.ua/courierpilot/v2/market/observations"
    private const val PROFILE_ENDPOINT = "https://wolt-api.zivkr.pp.ua/courierpilot/v2/market/profile"
    private const val PREFS = "courierpilot_market_intelligence"
    private const val KEY_SHARING = "sharing_enabled"
    private const val KEY_QUEUE = "queue"
    private const val KEY_LAST_UPLOAD = "last_upload"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_PROFILE_WOLT = "profile_wolt"
    private const val KEY_PROFILE_BOLT = "profile_bolt"
    private const val MAX_QUEUE = 300
    private const val BATCH_SIZE = 40
    private const val INITIAL_FLUSH_DELAY_MS = 2_000L
    private const val PROFILE_REFRESH_MS = 30L * 60L * 1000L
    private const val PROFILE_MAX_AGE_MS = 6L * 60L * 60L * 1000L
    private const val LOCAL_PROFILE_DAYS = 30L
    private const val MAX_RETRY_DELAY_MS = 15L * 60L * 1000L

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "CourierPilotMarket").apply { isDaemon = true }
    }
    private var scheduledFlush: ScheduledFuture<*>? = null
    private var retryDelayMs = 30_000L
    private val profileFetchInFlight = mutableSetOf<String>()

    fun sharingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHARING, false)

    fun setSharingEnabled(context: Context, enabled: Boolean): Boolean {
        val app = context.applicationContext
        val stored = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SHARING, enabled)
            .commit()
        if (!stored) return false
        if (enabled) {
            resume(app)
        } else {
            executor.execute {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_QUEUE).apply()
                scheduledFlush?.cancel(false)
                scheduledFlush = null
                retryDelayMs = 30_000L
            }
        }
        return sharingEnabled(app) == enabled
    }

    fun status(context: Context): MarketIntelligenceStatus {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return MarketIntelligenceStatus(
            sharingEnabled = prefs.getBoolean(KEY_SHARING, false),
            city = MarketCityResolver.cached(context),
            queued = readArray(prefs.getString(KEY_QUEUE, null)).length(),
            lastUploadAt = prefs.getLong(KEY_LAST_UPLOAD, 0L),
            lastError = prefs.getString(KEY_LAST_ERROR, "").orEmpty(),
            localWoltProfile = localProfileFor(context, "Wolt"),
            localBoltProfile = localProfileFor(context, "Bolt"),
            woltProfile = profileFor(context, "Wolt"),
            boltProfile = profileFor(context, "Bolt"),
        )
    }

    /** Called from Application startup. No network is done on the caller thread. */
    fun resume(context: Context) {
        val app = context.applicationContext
        executor.execute {
            MarketCityResolver.resolve(app) { city ->
                if (city == null) return@resolve
                executor.execute {
                    refreshProfileIfNeeded(app, city, "Wolt")
                    refreshProfileIfNeeded(app, city, "Bolt")
                    if (sharingEnabled(app) && queueSize(app) > 0) {
                        scheduleFlushOnExecutor(app, INITIAL_FLUSH_DELAY_MS)
                    }
                }
            }
        }
    }

    fun thresholdsFor(context: Context, platform: String): OfferDecisionThresholds? {
        val city = profileFor(context, platform)?.takeIf { it.ready }?.thresholds
        if (city != null) return city
        val cityInfo = MarketCityResolver.cached(context) ?: return null
        val samples = OfferDatabase.get(context).marketObservations(
            System.currentTimeMillis() - LOCAL_PROFILE_DAYS * 86_400_000L,
            cityInfo.key,
            localCurrencyCode(),
            normalizePlatform(platform) ?: return null,
        )
        val normalized = samples.mapNotNull { sample ->
            sample.money.major().toDouble().takeIf { it > 0.0 }?.let { it * 1000.0 / sample.fullRouteDistanceMeters }
        }
        val profile = AdaptiveMarketScoring.profile(
            normalized.mapIndexed { index, rate -> AdaptiveMarketSample(rate, samples[index].capturedAt, cityInfo.key, localCurrencyCode(), platform) },
            System.currentTimeMillis(),
        )?.takeIf { it.sampleCount >= AdaptiveMarketScoring.PERSONAL_MIN_SAMPLES }
        return profile?.let { OfferDecisionThresholds(it.p15, it.p35, it.p65, it.p85) }
    }

    fun profileFor(context: Context, platform: String): MarketProfile? {
        val normalizedPlatform = normalizePlatform(platform) ?: return null
        val key = profileKey(normalizedPlatform)
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null) ?: return null
        val parsed = parseProfile(raw, normalizedPlatform) ?: return null
        if (System.currentTimeMillis() - parsed.fetchedAt > PROFILE_MAX_AGE_MS) return null
        val city = MarketCityResolver.cached(context)
        if (city != null && parsed.cityKey != city.key) return null
        if (parsed.currencyCode != localCurrencyCode()) return null
        return parsed
    }

    fun localProfileFor(context: Context, platform: String): LocalMarketProfile? {
        val normalizedPlatform = normalizePlatform(platform) ?: return null
        val city = MarketCityResolver.cached(context) ?: return null
        val since = System.currentTimeMillis() - LOCAL_PROFILE_DAYS * 86_400_000L
        val database = OfferDatabase.get(context)

        fun rates(samples: List<LocalMarketSample>): List<Double> = samples.mapNotNull { sample ->
            val meters = sample.routeDistanceMeters.takeIf { it > 0 } ?: return@mapNotNull null
            sample.priceCents.takeIf { it > 0 }?.times(10.0)?.div(meters)
        }

        val platformSamples = database.localMarketSamplesSince(since, city.key, normalizedPlatform)
        LocalMarketScoring.profile(rates(platformSamples), source = "local_platform")?.let { return it }

        val allCitySamples = database.localMarketSamplesSince(since, city.key, platform = null)
        return LocalMarketScoring.profile(rates(allCitySamples), source = "local_all_platforms")
    }

    /**
     * Called only after a FULL Valhalla route exists. Pickup-only Bolt routing is intentionally not
     * eligible, so server €/km means the same thing as the live score.
     */
    fun onRouteResolved(
        context: Context,
        offerId: Long,
        record: OfferRecord,
        pedestrianRoute: RouteResult?,
        cyclewayRoute: RouteResult?,
    ) {
        val routeMeters = OfferDecisionEngine.averageValhallaDistanceMeters(pedestrianRoute, cyclewayRoute)
            ?.takeIf { it > 0 }
            ?: return
        if (record.priceCents <= 0) return
        val routeSource = when {
            pedestrianRoute != null && cyclewayRoute != null -> "valhalla_mean"
            cyclewayRoute != null -> "valhalla_cycle"
            else -> "valhalla_walk"
        }
        val app = context.applicationContext
        MarketCityResolver.resolve(app) { city ->
            if (city == null) return@resolve
            executor.execute {
                // The local order database is always the primary source for personalization.
                // Server sharing is optional and does not control whether the route economics are kept locally.
                OfferDatabase.get(app).updateMarketRoute(offerId, routeMeters, routeSource, city)
                val capturedCalendar = Calendar.getInstance().apply { timeInMillis = record.capturedAt }
                OfferDatabase.get(app).saveMarketObservation(
                    MarketObservation(
                        offerId = offerId,
                        capturedAt = record.capturedAt,
                        cityKey = city.key,
                        cityName = city.name,
                        countryCode = city.countryCode,
                        platform = normalizePlatform(record.platform) ?: record.platform,
                        money = MoneyAmount(record.priceCents.toLong(), "EUR", 2),
                        fullRouteDistanceMeters = routeMeters,
                        routeSource = "FULL_${routeSource}",
                        deliveryCount = record.deliveryCount,
                        localHour = capturedCalendar.get(Calendar.HOUR_OF_DAY),
                        localWeekday = ((capturedCalendar.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1,
                    ),
                )
                val sample = marketSample(offerId, record, routeMeters, routeSource, city)
                refreshProfileIfNeeded(app, city, record.platform)
                if (sharingEnabled(app)) {
                    persistSample(app, sample)
                    scheduleFlushOnExecutor(app, INITIAL_FLUSH_DELAY_MS)
                }
            }
        }
    }

    private fun marketSample(
        offerId: Long,
        record: OfferRecord,
        routeMeters: Int,
        routeSource: String,
        city: MarketCity,
    ): JSONObject {
        val calendar = Calendar.getInstance().apply { timeInMillis = record.capturedAt }
        return JSONObject()
            .put("id", "offer-${offerId}-${record.capturedAt}")
            .put("captured_at", record.capturedAt)
            .put("city_key", city.key)
            .put("city_name", city.name)
            .put("country_code", city.countryCode)
            .put("platform", normalizePlatform(record.platform) ?: record.platform.take(24))
            .put("currency_code", localCurrencyCode())
            .put("currency_fraction_digits", 2)
            .put("price_minor", record.priceCents)
            .put("route_distance_m", routeMeters)
            .put("route_source", "FULL_${routeSource}")
            .put("delivery_count", (record.deliveryCount ?: 1).coerceIn(1, 20))
            .put("local_hour", calendar.get(Calendar.HOUR_OF_DAY))
            .put("local_weekday", ((calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1)
    }

    private fun persistSample(context: Context, sample: JSONObject) {
        if (!sharingEnabled(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val queue = readArray(prefs.getString(KEY_QUEUE, null))
        val sampleId = sample.optString("id")
        for (i in 0 until queue.length()) {
            if (queue.optJSONObject(i)?.optString("id") == sampleId) return
        }
        queue.put(sample)
        val trimmed = JSONArray()
        val start = (queue.length() - MAX_QUEUE).coerceAtLeast(0)
        for (i in start until queue.length()) queue.optJSONObject(i)?.let(trimmed::put)
        prefs.edit().putString(KEY_QUEUE, trimmed.toString()).apply()
    }

    private fun scheduleFlushOnExecutor(context: Context, delayMs: Long) {
        if (!sharingEnabled(context)) return
        if (scheduledFlush?.isDone == false) return
        scheduledFlush = executor.schedule({
            scheduledFlush = null
            flushOneBatch(context)
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun flushOneBatch(context: Context) {
        if (!sharingEnabled(context)) return
        val batch = peekBatch(context) ?: return
        when (val result = uploadBatch(context, batch)) {
            is UploadOutcome.Success -> {
                removeSamples(context, batch.ids)
                markUploadSuccess(context)
                retryDelayMs = 30_000L
                if (queueSize(context) > 0) scheduleFlushOnExecutor(context, 500L)
            }
            is UploadOutcome.Drop -> {
                removeSamples(context, batch.ids)
                markUploadError(context, result.reason)
                retryDelayMs = 30_000L
                if (queueSize(context) > 0) scheduleFlushOnExecutor(context, 1_000L)
            }
            is UploadOutcome.Retry -> {
                markUploadError(context, result.reason)
                val delay = retryDelayMs
                retryDelayMs = (retryDelayMs * 2L).coerceAtMost(MAX_RETRY_DELAY_MS)
                scheduleFlushOnExecutor(context, delay)
            }
        }
    }

    private data class PendingBatch(val ids: Set<String>, val offers: JSONArray)

    private fun peekBatch(context: Context): PendingBatch? {
        val queue = readArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_QUEUE, null))
        if (queue.length() == 0) return null
        val out = JSONArray()
        val ids = linkedSetOf<String>()
        for (i in 0 until queue.length()) {
            if (out.length() >= BATCH_SIZE) break
            val item = queue.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            ids += id
            out.put(item)
        }
        return if (out.length() == 0) null else PendingBatch(ids, out)
    }

    private sealed interface UploadOutcome {
        data object Success : UploadOutcome
        data class Retry(val reason: String) : UploadOutcome
        data class Drop(val reason: String) : UploadOutcome
    }

    private fun uploadBatch(context: Context, batch: PendingBatch): UploadOutcome {
        val payload = JSONObject()
            .put("schema", 2)
            .put("install_id", RemoteDiagnostics.installationId(context))
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("version_code", BuildConfig.VERSION_CODE)
            .put("offers", batch.offers)
            .toString()
            .toByteArray(Charsets.UTF_8)

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(OFFERS_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "CourierPilot/${BuildConfig.VERSION_NAME}")
                setFixedLengthStreamingMode(payload.size)
            }
            connection.outputStream.use { it.write(payload) }
            when (val code = connection.responseCode) {
                in 200..299 -> {
                    connection.inputStream?.use { it.readBytes() }
                    UploadOutcome.Success
                }
                408, 425, 429 -> {
                    connection.errorStream?.close()
                    UploadOutcome.Retry("HTTP $code")
                }
                in 500..599 -> {
                    connection.errorStream?.close()
                    UploadOutcome.Retry("HTTP $code")
                }
                else -> {
                    connection.errorStream?.close()
                    UploadOutcome.Drop("HTTP $code")
                }
            }
        } catch (t: Throwable) {
            UploadOutcome.Retry(t.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private fun refreshProfileIfNeeded(context: Context, city: MarketCity, platform: String) {
        val normalizedPlatform = normalizePlatform(platform) ?: return
        val existing = profileFor(context, normalizedPlatform)
        if (existing != null && System.currentTimeMillis() - existing.fetchedAt < PROFILE_REFRESH_MS) return
        val inflightKey = "${city.key}:$normalizedPlatform"
        if (!profileFetchInFlight.add(inflightKey)) return
        try {
            fetchProfile(context, city, normalizedPlatform)?.let { profile ->
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(profileKey(normalizedPlatform), profile)
                    .apply()
            }
        } finally {
            profileFetchInFlight.remove(inflightKey)
        }
    }

    private fun fetchProfile(context: Context, city: MarketCity, platform: String): String? {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val url = URL("$PROFILE_ENDPOINT?city=${encode(city.key)}&currency=${encode(localCurrencyCode())}&platform=${encode(platform)}&hour=$hour")
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 6_000
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "CourierPilot/${BuildConfig.VERSION_NAME}")
            }
            if (connection.responseCode !in 200..299) {
                connection.errorStream?.close()
                return null
            }
            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(raw)
            if (json.optInt("schema") != 2) return null
            if (json.optJSONObject("city")?.optString("key") != city.key) return null
            json.put("_fetched_at", System.currentTimeMillis())
            json.toString()
        } catch (_: Throwable) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseProfile(raw: String, platform: String): MarketProfile? = runCatching {
        val json = JSONObject(raw)
        if (json.optInt("schema") != 2) return@runCatching null
        val city = json.optJSONObject("city") ?: return@runCatching null
        val edges = json.optJSONArray("bandEdges") ?: json.optJSONArray("percentileEdges") ?: return@runCatching null
        if (edges.length() != 4) return@runCatching null
        val values = List(4) { edges.optDouble(it, Double.NaN) }
        if (values.any { !it.isFinite() || it <= 0.0 }) return@runCatching null
        val thresholds = runCatching {
            OfferDecisionThresholds(values[0], values[1], values[2], values[3])
        }.getOrNull() ?: return@runCatching null
        val trendJson = json.optJSONObject("trend")
        val trend = trendJson?.let {
            MarketTrend(
                percent = it.optDouble("percent", Double.NaN),
                direction = it.optString("direction"),
            ).takeIf { parsed -> parsed.percent.isFinite() }
        }
        MarketProfile(
            cityKey = city.optString("key"),
            cityName = city.optString("name"),
            platform = platform,
            source = json.optString("source"),
            ready = json.optBoolean("ready", false),
            sampleCount = json.optInt("sampleCount", 0),
            uniqueInstallations = json.optInt("uniqueInstallations", 0),
            medianEurPerKm = json.optDouble("medianEurPerKm", Double.NaN).takeIf { it.isFinite() },
            confidence = json.optString("confidence", "none"),
            thresholds = thresholds,
            trend = trend,
            fetchedAt = json.optLong("_fetched_at", 0L),
            currencyCode = json.optString("currencyCode", "EUR").ifBlank { "EUR" },
        )
    }.getOrNull()

    private fun localCurrencyCode(): String = runCatching { java.util.Currency.getInstance(Locale.getDefault()).currencyCode }.getOrDefault("EUR")

    private fun removeSamples(context: Context, ids: Set<String>) {
        if (ids.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val queue = readArray(prefs.getString(KEY_QUEUE, null))
        val remaining = JSONArray()
        for (i in 0 until queue.length()) {
            val item = queue.optJSONObject(i) ?: continue
            if (item.optString("id") !in ids) remaining.put(item)
        }
        prefs.edit().putString(KEY_QUEUE, remaining.toString()).apply()
    }

    private fun queueSize(context: Context): Int =
        readArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_QUEUE, null)).length()

    private fun markUploadSuccess(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_UPLOAD, System.currentTimeMillis())
            .putString(KEY_LAST_ERROR, "")
            .apply()
    }

    private fun markUploadError(context: Context, error: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_ERROR, error.take(120))
            .apply()
    }

    private fun readArray(raw: String?): JSONArray = try {
        if (raw.isNullOrBlank()) JSONArray() else JSONArray(raw)
    } catch (_: Throwable) {
        JSONArray()
    }

    private fun profileKey(platform: String): String = when (normalizePlatform(platform)) {
        "Wolt" -> KEY_PROFILE_WOLT
        "Bolt" -> KEY_PROFILE_BOLT
        else -> "profile_unknown"
    }

    private fun normalizePlatform(value: String): String? = when (value.trim().lowercase(Locale.ROOT)) {
        "wolt" -> "Wolt"
        "bolt" -> "Bolt"
        else -> null
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
