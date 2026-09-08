package com.block154.courierpilot

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Fast authoritative resolver for Lithuanian Wolt addresses that carry a house number + postcode.
 *
 * Wolt can legally render a compact destination such as `55, Vilnius, 09110`. Photon/Android may
 * not infer the missing street, while Lithuania's public Address Register can: house number 55 +
 * postcode LT-09110 uniquely identifies Žirmūnų g. 55. The registry also returns WGS84 coordinates,
 * so no second geocoder round-trip is needed.
 */
internal object LithuanianAddressRegistryGeocoder {
    data class Resolution(
        val point: RoutePoint,
        val displayAddress: String,
    )

    fun resolve(address: String, city: MarketCity?): Resolution? {
        if (!city?.countryCode.equals("LT", ignoreCase = true)) return null
        val compact = parseHouseAndPostcode(address) ?: return null
        val body = JSONObject()
            .put(
                "filters",
                org.json.JSONArray().put(
                    JSONObject().put(
                        "addresses",
                        JSONObject()
                            .put("plot_or_building_number", JSONObject().put("exact", compact.houseNumber))
                            .put("postal_code", JSONObject().put("contains", compact.postcode)),
                    )
                )
            )
            .toString()

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                useCaches = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "CourierPilot/${BuildConfig.VERSION_NAME}")
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return null
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            parseResponse(response, compact, city)
        } catch (_: Throwable) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    internal fun parseResponse(body: String, compact: CompactAddress, city: MarketCity?): Resolution? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val items = root.optJSONArray("items") ?: return null
        val matches = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val number = item.optString("plot_or_building_number").trim()
                val postcode = item.optString("postal_code").uppercase(Locale.ROOT).removePrefix("LT-")
                if (!number.equals(compact.houseNumber, ignoreCase = true) || postcode != compact.postcode) continue

                // Postcode + exact building number is the primary registry identity. Lithuanian
                // locality names are inflected in registry output (e.g. Vilnius -> Vilniaus), so a
                // literal city-name gate would reject valid matches. Ambiguity is handled safely by
                // requiring exactly one matching registry item below.
                val point = parseWgs84Point(item.optJSONObject("geometry")?.optString("data").orEmpty()) ?: continue
                val street = item.optJSONObject("street")?.optString("full_name").orEmpty().trim()
                val display = buildList {
                    if (street.isNotBlank()) add("$street ${compact.houseNumber}") else add(compact.houseNumber)
                    city?.name?.takeIf(String::isNotBlank)?.let(::add)
                    add("LT-${compact.postcode}")
                }.joinToString(", ")
                add(Resolution(point, display))
            }
        }
        return matches.singleOrNull()
    }

    internal data class CompactAddress(val houseNumber: String, val postcode: String)

    internal fun parseHouseAndPostcode(address: String): CompactAddress? {
        val postcodeMatch = POSTCODE.find(address) ?: return null
        val postcode = postcodeMatch.groupValues.getOrNull(1)?.takeIf { it.length == 5 } ?: return null
        // The house number lives in the first comma-delimited address component. Strip a possible
        // apartment suffix (`55-12`) so the registry receives the building number (`55`).
        val addressHead = address.substring(0, postcodeMatch.range.first)
            .substringBefore(',')
            .trim()
        val house = HOUSE_AT_END.find(addressHead)?.groupValues?.getOrNull(1) ?: return null
        return CompactAddress(house, postcode)
    }

    private fun parseWgs84Point(value: String): RoutePoint? {
        val match = WGS84_POINT.find(value) ?: return null
        val longitude = match.groupValues[1].toDoubleOrNull() ?: return null
        val latitude = match.groupValues[2].toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return RoutePoint(latitude, longitude)
    }

    private fun cityMatches(expected: String, area: String, municipality: String): Boolean {
        if (expected.isBlank()) return true
        fun normalize(value: String): String = value
            .lowercase(Locale.ROOT)
            .replace(" m. sav.", "")
            .replace(" m.", "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        val wanted = normalize(expected)
        if (wanted.isBlank()) return true
        return normalize(area).contains(wanted) || normalize(municipality).contains(wanted)
    }

    private val POSTCODE = Regex("(?i)(?:LT[- ]?)?(\\d{5})\\b")
    private val HOUSE_AT_END = Regex("(?iu)(\\d+[A-ZĄČĘĖĮŠŲŪŽ]?)(?:\\s*-\\s*\\d+[A-ZĄČĘĖĮŠŲŪŽ]?)?\\s*$")
    private val WGS84_POINT = Regex("(?i)SRID=4326;POINT\\(([-+0-9.]+)\\s+([-+0-9.]+)\\)")
    private const val ENDPOINT = "https://boundaries.biip.lt/v1/addresses/search?srid=4326&size=5"
    private const val CONNECT_TIMEOUT_MS = 1_800
    private const val READ_TIMEOUT_MS = 1_800
}

/** Persistent address-to-coordinate cache shared by Android, Photon and LT registry resolutions. */
internal object RouteGeocodePersistentCache {
    private const val PREFS = "courierpilot_geocode_cache_v1"
    private const val TTL_MS = 30L * 24L * 60L * 60L * 1000L
    private const val MAX_ENTRIES = 600

    fun get(context: Context, key: String): RoutePoint? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null) ?: return null
        val parts = raw.split('|')
        if (parts.size != 3) return null
        val expiresAt = parts[2].toLongOrNull() ?: return null
        if (expiresAt <= System.currentTimeMillis()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key).apply()
            return null
        }
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        return RoutePoint(lat, lon)
    }

    fun put(context: Context, key: String, point: RoutePoint) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.all.size >= MAX_ENTRIES) {
            val editor = prefs.edit()
            val now = System.currentTimeMillis()
            prefs.all.entries.take(100).forEach { entry ->
                val expiry = (entry.value as? String)?.substringAfterLast('|')?.toLongOrNull() ?: 0L
                if (expiry <= now) editor.remove(entry.key)
            }
            editor.apply()
        }
        prefs.edit()
            .putString(key, "${point.latitude}|${point.longitude}|${System.currentTimeMillis() + TTL_MS}")
            .apply()
    }
}
