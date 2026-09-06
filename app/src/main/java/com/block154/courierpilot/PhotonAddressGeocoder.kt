package com.block154.courierpilot

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Network fallback for OEM Android Geocoder failures.
 *
 * Wolt already gives us a textual delivery address. Some Android 16/ColorOS geocoder calls on the
 * real courier device never return, so we race the platform geocoder against Photon and accept the
 * first valid result. No GPS coordinate is sent to Photon; the query is constrained only by the
 * city/country already present in the app's city cache.
 */
internal object PhotonAddressGeocoder {
    fun resolve(address: String, city: MarketCity?): RoutePoint? {
        val query = buildQuery(address, city)
        if (query.isBlank()) return null
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = URL("https://photon.komoot.io/api/?q=$encoded&limit=5")
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "CourierPilot/${BuildConfig.VERSION_NAME}")
            }
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parsePoint(body, city?.countryCode)
        } catch (_: Throwable) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    internal fun parsePoint(body: String, countryCode: String?): RoutePoint? {
        val features = runCatching { JSONObject(body).optJSONArray("features") }.getOrNull() ?: return null
        val normalizedCountry = countryCode?.trim()?.uppercase()
        var first: RoutePoint? = null
        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue
            val properties = feature.optJSONObject("properties")
            val featureCountry = properties?.optString("countrycode")?.trim()?.uppercase().orEmpty()
            val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (coordinates.length() < 2) continue
            val longitude = coordinates.optDouble(0, Double.NaN)
            val latitude = coordinates.optDouble(1, Double.NaN)
            if (!latitude.isFinite() || !longitude.isFinite()) continue
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) continue
            val point = RoutePoint(latitude, longitude)
            if (first == null) first = point
            if (normalizedCountry.isNullOrBlank() || featureCountry.isBlank() || featureCountry == normalizedCountry) {
                return point
            }
        }
        return first
    }

    private fun buildQuery(address: String, city: MarketCity?): String {
        val clean = address.trim()
        if (clean.isBlank()) return ""
        val cityName = city?.name?.trim().orEmpty()
        val country = city?.countryCode?.trim()?.uppercase().orEmpty()
        val lower = clean.lowercase()
        return buildList {
            add(clean)
            if (cityName.isNotBlank() && !lower.contains(cityName.lowercase())) add(cityName)
            if (country.length == 2 && !lower.contains(country.lowercase())) add(country)
        }.joinToString(", ")
    }

    private const val CONNECT_TIMEOUT_MS = 2_500
    private const val READ_TIMEOUT_MS = 2_500
}
