package com.block154.courierpilot

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    val medianNativeMoneyPerKm: Double?,
    val confidence: String,
    val thresholds: OfferDecisionThresholds?,
    val trend: MarketTrend?,
    val fetchedAt: Long,
    val currencyCode: String,
)

internal data class MarketHistoryPoint(
    val bucket: String,
    val sampleCount: Int,
    val medianNativeMoneyPerKm: Double,
    val p25: Double,
    val p75: Double,
    val medianPriceMinor: Long? = null,
    val medianDistanceMeters: Int? = null,
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
 * Market profiles are useful even when sharing is disabled. Missing evidence stays in LEARNING;
 * there is no universal money/km fallback in the live scoring path.
 */
internal object MarketIntelligence {
    private const val OFFERS_ENDPOINT = "https://wolt-api.zivkr.pp.ua/courierpilot/v2/market/observations"
    private const val PROFILE_ENDPOINT = "https://wolt-api.zivkr.pp.ua/courierpilot/v2/market/profile"
    private const val HISTORY_ENDPOINT = "https://wolt-api.zivkr.pp.ua/courierpilot/v2/market/history"
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
    private const val HISTORY_DAYS = 730L
    private const val HISTORY_MAX_AGE_MS = 6L * 60L * 60L * 1000L
    private const val MAX_RETRY_DELAY_MS = 15L * 60L * 1000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "CourierPilotMarket").apply { isDaemon = true }
    }
    private var scheduledFlush: ScheduledFuture<*>? = null
    private var retryDelayMs = 30_000L
    private val profileFetchInFlight = mutableSetOf<String>()
    private val historyFetchInFlight = mutableSetOf<String>()

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
        val woltCurrency = currencyFor(context, "Wolt")
        val boltCurrency = currencyFor(context, "Bolt")
        return MarketIntelligenceStatus(
            sharingEnabled = prefs.getBoolean(KEY_SHARING, false),
            city = MarketCityResolver.cached(context),
            queued = readArray(prefs.getString(KEY_QUEUE, null)).length(),
            lastUploadAt = prefs.getLong(KEY_LAST_UPLOAD, 0L),
            lastError = prefs.getString(KEY_LAST_ERROR, "").orEmpty(),
            localWoltProfile = localProfileFor(context, "Wolt", woltCurrency),
            localBoltProfile = localProfileFor(context, "Bolt", boltCurrency),
            woltProfile = profileFor(context, "Wolt", woltCurrency),
            boltProfile = profileFor(context, "Bolt", boltCurrency),
        )
    }

    fun currencyFor(context: Context, platform: String): String {
        val normalizedPlatform = normalizePlatform(platform) ?: return localCurrencyCode()
        val city = MarketCityResolver.cached(context) ?: return localCurrencyCode()
        return OfferDatabase.get(context).latestMarketCurrency(city.key, normalizedPlatform) ?: localCurrencyCode()
    }

    /** Called from Application startup. No network is done on the caller thread. */
    fun resume(context: Context) {
        val app = context.applicationContext
        executor.execute {
            MarketCityResolver.resolve(app) { city ->
                if (city == null) return@resolve
                executor.execute {
                    refreshProfileIfNeeded(app, city, "Wolt", currencyFor(app, "Wolt"))
                    refreshProfileIfNeeded(app, city, "Bolt", currencyFor(app, "Bolt"))
                    if (sharingEnabled(app) && queueSize(app) > 0) {
                        scheduleFlushOnExecutor(app, INITIAL_FLUSH_DELAY_MS)
                    }
                }
            }
        }
    }

    fun thresholdsFor(context: Context, platform: String, currencyCode: String): OfferDecisionThresholds? {
        val city = profileFor(context, platform, currencyCode)?.takeIf { it.ready }?.thresholds
        val cityInfo = MarketCityResolver.cached(context) ?: return null
        val samples = OfferDatabase.get(context).marketObservations(
            System.currentTimeMillis() - LOCAL_PROFILE_DAYS * 86_400_000L,
            cityInfo.key,
            currencyCode,
            normalizePlatform(platform) ?: return null,
        )
        val normalized = samples.mapNotNull { sample ->
            sample.money.major().toDouble().takeIf { it > 0.0 }?.let { rate ->
                AdaptiveMarketSample(rate * 1000.0 / sample.fullRouteDistanceMeters, sample.capturedAt, cityInfo.key, currencyCode, platform)
            }
        }
        val profile = AdaptiveMarketScoring.profile(
            normalized,
            System.currentTimeMillis(),
        )?.takeIf { it.sampleCount >= AdaptiveMarketScoring.PERSONAL_MIN_SAMPLES }
        val personal = profile?.let { OfferDecisionThresholds(it.p15, it.p35, it.p65, it.p85) }
        return when {
            personal != null && city != null -> {
                val weight = AdaptiveMarketScoring.personalWeight(profile.effectiveSampleCount)
                OfferDecisionThresholds(
                    city.terribleBelow * (1 - weight) + personal.terribleBelow * weight,
                    city.badBelow * (1 - weight) + personal.badBelow * weight,
                    city.okAtMost * (1 - weight) + personal.okAtMost * weight,
                    city.goodBelow * (1 - weight) + personal.goodBelow * weight,
                )
            }
            personal != null -> personal
            else -> city
        }
    }

    fun profileFor(context: Context, platform: String, currencyCode: String): MarketProfile? {
        val normalizedPlatform = normalizePlatform(platform) ?: return null
        val key = profileKey(normalizedPlatform, currencyCode)
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null) ?: return null
        val parsed = parseProfile(raw, normalizedPlatform) ?: return null
        if (System.currentTimeMillis() - parsed.fetchedAt > PROFILE_MAX_AGE_MS) return null
        val city = MarketCityResolver.cached(context)
        if (city != null && parsed.cityKey != city.key) return null
        if (parsed.currencyCode != currencyCode) return null
        return parsed
    }

    fun localProfileFor(context: Context, platform: String, currencyCode: String): LocalMarketProfile? {
        val normalizedPlatform = normalizePlatform(platform) ?: return null
        val city = MarketCityResolver.cached(context) ?: return null
        val since = System.currentTimeMillis() - LOCAL_PROFILE_DAYS * 86_400_000L
        val database = OfferDatabase.get(context)

        val samples = database.marketObservations(since, city.key, currencyCode, normalizedPlatform)
        val normalized = samples.mapNotNull { sample ->
            val meters = sample.fullRouteDistanceMeters.takeIf { it > 0 } ?: return@mapNotNull null
            val major = sample.money.major().toDouble().takeIf { it > 0.0 } ?: return@mapNotNull null
            AdaptiveMarketSample(major * 1000.0 / meters, sample.capturedAt, city.key, currencyCode, normalizedPlatform)
        }
        val profile = AdaptiveMarketScoring.profile(normalized, System.currentTimeMillis()) ?: return null
        if (profile.sampleCount < AdaptiveMarketScoring.PERSONAL_MIN_SAMPLES) return null
        return LocalMarketProfile(
            sampleCount = profile.sampleCount,
            medianNativeMoneyPerKm = profile.median,
            thresholds = OfferDecisionThresholds(profile.p15, profile.p35, profile.p65, profile.p85),
            source = "local_platform",
        )
    }

    fun localHistoryFor(context: Context, platform: String, currencyCode: String, period: String): List<MarketHistoryPoint> {
        val normalizedPlatform = normalizePlatform(platform) ?: return emptyList()
        val city = MarketCityResolver.cached(context) ?: return emptyList()
        val bucket = when (period.lowercase(Locale.ROOT)) {
            "day" -> MarketObservationBucket.DAY
            "month" -> MarketObservationBucket.MONTH
            else -> MarketObservationBucket.WEEK
        }
        val groups = OfferDatabase.get(context).marketObservationBuckets(
            since = System.currentTimeMillis() - HISTORY_DAYS * 86_400_000L,
            cityKey = city.key,
            currencyCode = currencyCode,
            platform = normalizedPlatform,
            bucket = bucket,
        )
        return groups.entries.sortedByDescending { it.key }.mapNotNull { (label, samples) ->
            val rates = samples.mapNotNull { sample ->
                val meters = sample.fullRouteDistanceMeters.takeIf { it > 0 } ?: return@mapNotNull null
                val major = sample.money.major().toDouble().takeIf { it > 0 } ?: return@mapNotNull null
                major * 1000.0 / meters
            }.sorted()
            if (rates.isEmpty()) return@mapNotNull null
            MarketHistoryPoint(
                bucket = label,
                sampleCount = rates.size,
                medianNativeMoneyPerKm = quantile(rates, 0.50),
                p25 = quantile(rates, 0.25),
                p75 = quantile(rates, 0.75),
            )
        }
    }

    fun cityHistoryFor(context: Context, platform: String, currencyCode: String, period: String): List<MarketHistoryPoint> {
        val normalizedPlatform = normalizePlatform(platform) ?: return emptyList()
        val city = MarketCityResolver.cached(context) ?: return emptyList()
        val key = historyKey(city.key, normalizedPlatform, currencyCode, period)
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null) ?: return emptyList()
        return parseHistory(raw, city.key, normalizedPlatform, currencyCode, period)
    }

    fun refreshHistory(
        context: Context,
        platform: String,
        currencyCode: String,
        period: String,
        onComplete: () -> Unit = {},
    ) {
        val app = context.applicationContext
        val normalizedPlatform = normalizePlatform(platform) ?: return
        val normalizedPeriod = normalizePeriod(period)
        val city = MarketCityResolver.cached(app)
        if (city == null) {
            MarketCityResolver.resolve(app) { resolved ->
                if (resolved != null) refreshHistory(app, normalizedPlatform, currencyCode, normalizedPeriod, onComplete)
                else mainHandler.post(onComplete)
            }
            return
        }
        val key = historyKey(city.key, normalizedPlatform, currencyCode, normalizedPeriod)
        val existing = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
        val fetchedAt = runCatching { JSONObject(existing.orEmpty()).optLong("_fetched_at", 0L) }.getOrDefault(0L)
        if (fetchedAt > 0 && System.currentTimeMillis() - fetchedAt < HISTORY_MAX_AGE_MS) {
            mainHandler.post(onComplete)
            return
        }
        val inFlightKey = "${city.key}:$currencyCode:$normalizedPlatform:$normalizedPeriod"
        synchronized(historyFetchInFlight) {
            if (!historyFetchInFlight.add(inFlightKey)) return
        }
        executor.execute {
            try {
                fetchHistory(city, normalizedPlatform, currencyCode, normalizedPeriod)?.let { raw ->
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, raw).apply()
                }
            } finally {
                synchronized(historyFetchInFlight) { historyFetchInFlight.remove(inFlightKey) }
                mainHandler.post(onComplete)
            }
        }
    }

    /**
     * Called only after a FULL Valhalla route exists. Pickup-only Bolt routing is intentionally not
     * eligible, so server native-money/km means the same thing as the live score.
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
        if (record.priceCents <= 0 || record.currencyCode.isBlank()) return
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
                        money = MoneyAmount(record.priceCents.toLong(), record.currencyCode, record.currencyFractionDigits),
                        fullRouteDistanceMeters = routeMeters,
                        routeSource = "FULL_${routeSource}",
                        deliveryCount = record.deliveryCount,
                        localHour = capturedCalendar.get(Calendar.HOUR_OF_DAY),
                        localWeekday = ((capturedCalendar.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1,
                    ),
                )
                val sample = marketSample(offerId, record, routeMeters, routeSource, city)
                refreshProfileIfNeeded(app, city, record.platform, record.currencyCode)
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
            .put("currency_code", record.currencyCode)
            .put("currency_fraction_digits", record.currencyFractionDigits)
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

    private fun refreshProfileIfNeeded(context: Context, city: MarketCity, platform: String, currencyCode: String) {
        val normalizedPlatform = normalizePlatform(platform) ?: return
        val existing = profileFor(context, normalizedPlatform, currencyCode)
        if (existing != null && System.currentTimeMillis() - existing.fetchedAt < PROFILE_REFRESH_MS) return
        val inflightKey = "${city.key}:$currencyCode:$normalizedPlatform"
        if (!profileFetchInFlight.add(inflightKey)) return
        try {
            fetchProfile(context, city, normalizedPlatform, currencyCode)?.let { profile ->
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(profileKey(normalizedPlatform, currencyCode), profile)
                    .apply()
            }
        } finally {
            profileFetchInFlight.remove(inflightKey)
        }
    }

    private fun fetchProfile(context: Context, city: MarketCity, platform: String, currencyCode: String): String? {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val url = URL("$PROFILE_ENDPOINT?city=${encode(city.key)}&currency=${encode(currencyCode)}&platform=${encode(platform)}&hour=$hour")
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

    private fun fetchHistory(city: MarketCity, platform: String, currencyCode: String, period: String): String? {
        val url = URL("$HISTORY_ENDPOINT?city=${encode(city.key)}&currency=${encode(currencyCode)}&platform=${encode(platform)}&period=${encode(period)}")
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
                null
            } else {
                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(raw)
                if (json.optInt("schema") != 2 || json.optString("city") != city.key ||
                    json.optString("currencyCode") != currencyCode || json.optString("platform") != platform ||
                    json.optString("period") != period) return null
                json.put("_fetched_at", System.currentTimeMillis())
                json.toString()
            }
        } catch (_: Throwable) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseHistory(
        raw: String,
        cityKey: String,
        platform: String,
        currencyCode: String,
        period: String,
    ): List<MarketHistoryPoint> = runCatching {
        val json = JSONObject(raw)
        if (json.optInt("schema") != 2 || json.optString("city") != cityKey ||
            json.optString("currencyCode") != currencyCode || json.optString("platform") != platform ||
            json.optString("period") != normalizePeriod(period)) return@runCatching emptyList()
        val buckets = json.optJSONArray("buckets") ?: return@runCatching emptyList()
        buildList {
            for (index in 0 until buckets.length()) {
                val item = buckets.optJSONObject(index) ?: continue
                val median = item.optDouble("medianNativeMoneyPerKm", Double.NaN)
                val p25 = item.optDouble("p25", Double.NaN)
                val p75 = item.optDouble("p75", Double.NaN)
                val count = item.optInt("sampleCount", 0)
                if (!median.isFinite() || !p25.isFinite() || !p75.isFinite() || count <= 0) continue
                add(
                    MarketHistoryPoint(
                        bucket = item.optString("bucket"),
                        sampleCount = count,
                        medianNativeMoneyPerKm = median,
                        p25 = p25,
                        p75 = p75,
                        medianPriceMinor = item.optLong("medianPriceMinor", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE },
                        medianDistanceMeters = item.optInt("medianDistanceM", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                    ),
                )
            }
        }.sortedByDescending { it.bucket }
    }.getOrDefault(emptyList())

    private fun parseProfile(raw: String, platform: String): MarketProfile? = runCatching {
        val json = JSONObject(raw)
        if (json.optInt("schema") != 2) return@runCatching null
        val city = json.optJSONObject("city") ?: return@runCatching null
        val currencyCode = json.optString("currencyCode").takeIf { it.matches(Regex("[A-Z]{3}")) }
            ?: return@runCatching null
        val ready = json.optBoolean("ready", false)
        val edges = json.optJSONArray("bandEdges") ?: json.optJSONArray("percentileEdges")
        val thresholds = if (edges != null && edges.length() == 4) {
            val values = List(4) { edges.optDouble(it, Double.NaN) }
            if (values.any { !it.isFinite() || it <= 0.0 }) null
            else runCatching { OfferDecisionThresholds(values[0], values[1], values[2], values[3]) }.getOrNull()
        } else null
        if (ready && thresholds == null) return@runCatching null
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
            ready = ready,
            sampleCount = json.optInt("sampleCount", 0),
            uniqueInstallations = json.optInt("uniqueInstallations", 0),
            medianNativeMoneyPerKm = json.optDouble("medianNativeMoneyPerKm", Double.NaN).takeIf { it.isFinite() },
            confidence = json.optString("confidence", "NOT_READY"),
            thresholds = thresholds,
            trend = trend,
            fetchedAt = json.optLong("_fetched_at", 0L),
            currencyCode = currencyCode,
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

    private fun profileKey(platform: String, currencyCode: String): String {
        val base = when (normalizePlatform(platform)) {
            "Wolt" -> KEY_PROFILE_WOLT
            "Bolt" -> KEY_PROFILE_BOLT
            else -> "profile_unknown"
        }
        return "${base}_${currencyCode.uppercase(Locale.ROOT)}"
    }

    private fun historyKey(cityKey: String, platform: String, currencyCode: String, period: String): String =
        "history_${cityKey}_${platform.lowercase(Locale.ROOT)}_${currencyCode.uppercase(Locale.ROOT)}_${normalizePeriod(period)}"

    private fun normalizePeriod(value: String): String = when (value.lowercase(Locale.ROOT)) {
        "day" -> "day"
        "month" -> "month"
        else -> "week"
    }

    private fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.size == 1) return sorted.first()
        val position = (sorted.size - 1) * q.coerceIn(0.0, 1.0)
        val low = kotlin.math.floor(position).toInt()
        val high = kotlin.math.ceil(position).toInt()
        if (low == high) return sorted[low]
        val fraction = position - low
        return sorted[low] * (1.0 - fraction) + sorted[high] * fraction
    }

    private fun normalizePlatform(value: String): String? = when (value.trim().lowercase(Locale.ROOT)) {
        "wolt" -> "Wolt"
        "bolt" -> "Bolt"
        else -> null
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
