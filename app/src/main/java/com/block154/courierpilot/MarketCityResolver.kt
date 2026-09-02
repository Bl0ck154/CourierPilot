package com.block154.courierpilot

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal data class MarketCity(
    val key: String,
    val name: String,
    val countryCode: String,
    val resolvedAt: Long,
)

/**
 * Converts an on-device location fix to city-level identity. Exact coordinates never leave the
 * device: only the resolved city/country strings are cached and later eligible for market upload.
 */
internal object MarketCityResolver {
    private const val PREFS = "courierpilot_market_city"
    private const val KEY_CITY_KEY = "city_key"
    private const val KEY_CITY_NAME = "city_name"
    private const val KEY_COUNTRY = "country_code"
    private const val KEY_RESOLVED_AT = "resolved_at"
    private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L

    private val resolving = AtomicBoolean(false)

    fun cached(context: Context): MarketCity? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = prefs.getString(KEY_CITY_KEY, null).orEmpty()
        val name = prefs.getString(KEY_CITY_NAME, null).orEmpty()
        val country = prefs.getString(KEY_COUNTRY, null).orEmpty()
        val resolvedAt = prefs.getLong(KEY_RESOLVED_AT, 0L)
        if (key.isBlank() || name.isBlank() || country.length != 2 || resolvedAt <= 0L) return null
        return MarketCity(key, name, country, resolvedAt)
    }

    fun resolve(context: Context, callback: (MarketCity?) -> Unit = {}) {
        val app = context.applicationContext
        val cached = cached(app)
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.resolvedAt < CACHE_TTL_MS) {
            callback(cached)
            return
        }

        // A stale city is still a much better market key than dropping the sample entirely. Return
        // it immediately while one best-effort reverse-geocode refresh runs in parallel.
        if (cached != null) callback(cached)
        if (!resolving.compareAndSet(false, true)) {
            if (cached == null) callback(null)
            return
        }

        val fix = RouteResearchLocation.bestLastKnown(app)
        if (fix == null || !Geocoder.isPresent()) {
            resolving.set(false)
            if (cached == null) callback(null)
            return
        }

        val geocoder = Geocoder(app, Locale.getDefault())
        fun complete(address: Address?) {
            val resolved = address?.toMarketCity(now)
            if (resolved != null) persist(app, resolved)
            resolving.set(false)
            if (cached == null) callback(resolved)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                geocoder.getFromLocation(fix.point.latitude, fix.point.longitude, 1) { results ->
                    app.mainExecutor.execute { complete(results.firstOrNull()) }
                }
            }.onFailure {
                resolving.set(false)
                if (cached == null) callback(null)
            }
        } else {
            Thread {
                val address = runCatching {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(fix.point.latitude, fix.point.longitude, 1)?.firstOrNull()
                }.getOrNull()
                app.mainExecutor.execute { complete(address) }
            }.apply {
                name = "CourierPilotMarketCity"
                isDaemon = true
                start()
            }
        }
    }

    private fun Address.toMarketCity(now: Long): MarketCity? {
        val country = countryCode.orEmpty().trim().uppercase(Locale.ROOT)
        if (country.length != 2) return null
        val city = sequenceOf(locality, subAdminArea, adminArea)
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?: return null
        val slug = citySlug(city)
        if (slug.length < 2) return null
        return MarketCity(
            key = "${country.lowercase(Locale.ROOT)}-$slug",
            name = city.take(80),
            countryCode = country,
            resolvedAt = now,
        )
    }

    private fun persist(context: Context, city: MarketCity) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CITY_KEY, city.key)
            .putString(KEY_CITY_NAME, city.name)
            .putString(KEY_COUNTRY, city.countryCode)
            .putLong(KEY_RESOLVED_AT, city.resolvedAt)
            .apply()
    }

    internal fun citySlug(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(56)
}
