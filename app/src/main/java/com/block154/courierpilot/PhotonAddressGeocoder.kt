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
    fun resolve(address: String, city: MarketCity?, reference: RoutePoint? = null): RoutePoint? {
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
            parsePoint(
                body = body,
                countryCode = city?.countryCode,
                requestedAddress = address,
                cityName = city?.name,
                reference = reference,
            )
        } catch (_: Throwable) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    internal fun parsePoint(body: String, countryCode: String?): RoutePoint? =
        parsePoint(body, countryCode, requestedAddress = null, cityName = null, reference = null)

    /**
     * Photon can return several same-country places for a street query. Never select a candidate
     * merely because it appears first: prefer the requested city, postcode, house number and street.
     * The optional phone reference is used only as a local tie-breaker and is never sent to Photon.
     */
    internal fun parsePoint(
        body: String,
        countryCode: String?,
        requestedAddress: String?,
        cityName: String?,
        reference: RoutePoint?,
    ): RoutePoint? {
        val features = runCatching { JSONObject(body).optJSONArray("features") }.getOrNull() ?: return null
        val normalizedCountry = countryCode?.trim()?.uppercase()
        val request = requestedAddress.orEmpty()
        val requestedHouse = HOUSE_NUMBER.find(request)?.groupValues?.getOrNull(1)?.let(::normalizeToken).orEmpty()
        val requestedPostcode = POSTCODE.find(request)?.value.orEmpty()
        val requestedStreet = normalizeStreet(request)
        val requestedCity = normalizeToken(cityName.orEmpty())

        data class Candidate(val point: RoutePoint, val score: Double, val countryMatches: Boolean)
        val candidates = mutableListOf<Candidate>()
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
            val countryMatches = normalizedCountry.isNullOrBlank() || featureCountry.isBlank() || featureCountry == normalizedCountry
            var score = if (countryMatches) 1000.0 else 0.0

            val featureCity = normalizeToken(properties?.optString("city").orEmpty())
            if (requestedCity.isNotBlank() && featureCity == requestedCity) score += 220.0

            val featurePostcode = properties?.optString("postcode").orEmpty()
            if (requestedPostcode.isNotBlank() && featurePostcode == requestedPostcode) score += 180.0

            val featureHouse = normalizeToken(properties?.optString("housenumber").orEmpty())
            if (requestedHouse.isNotBlank() && featureHouse == requestedHouse) score += 260.0

            val featureStreet = normalizeStreet(properties?.optString("street").orEmpty())
            if (requestedStreet.isNotBlank() && featureStreet.isNotBlank() &&
                (requestedStreet.contains(featureStreet) || featureStreet.contains(requestedStreet))
            ) score += 240.0

            if (reference != null) {
                // Distance is a tie-breaker only. Address semantics above dominate selection.
                score -= kotlin.math.sqrt(distanceSquared(reference, point)) / 10_000.0
            }
            candidates += Candidate(point, score, countryMatches)
        }
        val countryPool = candidates.filter { it.countryMatches }.ifEmpty { candidates }
        return countryPool.maxByOrNull { it.score }?.point
    }

    private fun normalizeToken(value: String): String = value
        .lowercase()
        .replace(Regex("""[^\p{L}\p{N}]+"""), "")

    private fun normalizeStreet(value: String): String = value
        .lowercase()
        .replace("gatvė", "g")
        .replace("gatve", "g")
        .replace(Regex("""\bg\.?\b"""), "g")
        .replace(Regex("""\b\d+[a-zA-Z]?\b.*$"""), "")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()

    private fun distanceSquared(a: RoutePoint, b: RoutePoint): Double {
        val latScale = 111_320.0
        val lonScale = 111_320.0 * kotlin.math.cos(Math.toRadians(a.latitude))
        val dy = (a.latitude - b.latitude) * latScale
        val dx = (a.longitude - b.longitude) * lonScale
        return dx * dx + dy * dy
    }

    private val HOUSE_NUMBER = Regex("""(?iu)\b(\d+[a-zA-Z]?)\b""")
    private val POSTCODE = Regex("""(?<!\d)\d{5}(?!\d)""")

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
