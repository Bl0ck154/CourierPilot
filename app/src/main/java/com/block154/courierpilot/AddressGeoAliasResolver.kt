package com.block154.courierpilot

import android.content.Context
import android.location.Address
import android.location.Geocoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Best-effort second-stage identity resolver for genuinely different-language address strings.
 *
 * It is intentionally NOT used for every address. We call the device geocoder only after local
 * normalization failed and a newly-created row has older candidates with the same house number.
 * A merge requires the same house number plus near-identical geocoded coordinates, so a translated
 * street name can match while two different streets that merely look similar locally do not.
 */
internal object AddressGeoAliasResolver {
    private const val PREFS = "courierpilot_address_geo_cache_v1"
    private const val CACHE_PREFIX = "geo_"
    private const val FIELD_SEPARATOR = "\u001F"
    private const val MAX_CANDIDATES = 8
    private const val MERGE_DISTANCE_METERS = 18.0
    private const val AMBIGUOUS_DISTANCE_MARGIN_METERS = 7.0
    private const val CACHE_TTL_MS = 60L * 24L * 60L * 60L * 1000L

    private val executor = Executors.newSingleThreadExecutor()

    private data class GeoIdentity(
        val sourceKey: String,
        val latitude: Double,
        val longitude: Double,
        val houseNumber: String,
        val canonicalDisplay: String?,
        val resolvedAt: Long,
    )

    fun scheduleForPossibleAlias(
        context: Context,
        database: CourierMetaDatabase,
        saved: SmartAddressSaveResult,
        rawAddress: String,
    ) {
        if (!saved.inserted || !Geocoder.isPresent()) return
        val identity = DeliveryAddressNormalizer.identity(rawAddress) ?: return
        val candidates = AddressMemoryResolver.addressesWithHouseNumber(
            database,
            identity.houseNumber,
            excludeId = saved.addressId,
        ).take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return

        val app = context.applicationContext
        executor.execute {
            runCatching {
                val current = resolveCachedOrNetwork(app, rawAddress, identity) ?: return@runCatching
                val matches = candidates.mapNotNull { candidate ->
                    val candidateIdentity = DeliveryAddressNormalizer.identity(candidate.displayAddress)
                        ?: return@mapNotNull null
                    val candidateGeo = resolveCachedOrNetwork(app, candidate.displayAddress, candidateIdentity)
                        ?: return@mapNotNull null
                    if (!sameHouse(current.houseNumber, candidateGeo.houseNumber)) return@mapNotNull null
                    val distance = distanceMeters(current, candidateGeo)
                    distance.takeIf { it <= MERGE_DISTANCE_METERS }?.let { candidate to it }
                }.sortedBy { it.second }

                val best = matches.firstOrNull() ?: return@runCatching
                val second = matches.getOrNull(1)
                if (second != null && second.second - best.second < AMBIGUOUS_DISTANCE_MARGIN_METERS) {
                    return@runCatching
                }

                val official = current.canonicalDisplay
                    ?.let(DeliveryAddressNormalizer::display)
                    ?: best.first.displayAddress
                val merged = AddressMemoryResolver.mergeRecords(
                    app,
                    database,
                    best.first.id,
                    saved.addressId,
                    preferredDisplay = official,
                ) ?: return@runCatching

                AddressMemoryResolver.rememberAlias(
                    app,
                    identity.key,
                    merged.buildingKey,
                    merged.displayAddress,
                )
                CaptureEventLog.append(
                    app,
                    stage = "address_geo_alias",
                    message = "Merged translated/alternate address by geocoded building identity",
                    dedupeWindowMs = 60_000L,
                )
            }.onFailure {
                CaptureEventLog.append(
                    app,
                    stage = "address_geo_alias_failed",
                    message = it.javaClass.simpleName,
                    dedupeWindowMs = 60_000L,
                )
            }
        }
    }

    private fun resolveCachedOrNetwork(
        context: Context,
        query: String,
        identity: DeliveryAddressIdentity,
    ): GeoIdentity? {
        readCache(context, identity.key)?.takeIf {
            System.currentTimeMillis() - it.resolvedAt <= CACHE_TTL_MS
        }?.let { return it }

        val geocoder = Geocoder(context, Locale.forLanguageTag("lt-LT"))
        val addresses = geocode(geocoder, context, query)
        val chosen = chooseResult(addresses, identity.houseNumber) ?: return null
        val house = normalizeHouseNumber(chosen.subThoroughfare)
            ?: normalizeHouseNumber(chosen.featureName)
            ?: identity.houseNumber
        val canonical = chosen.getAddressLine(0)?.takeIf(String::isNotBlank)
        val result = GeoIdentity(
            sourceKey = identity.key,
            latitude = chosen.latitude,
            longitude = chosen.longitude,
            houseNumber = house,
            canonicalDisplay = canonical,
            resolvedAt = System.currentTimeMillis(),
        )
        writeCache(context, result)
        return result
    }

    @Suppress("DEPRECATION")
    private fun geocode(geocoder: Geocoder, context: Context, query: String): List<Address> {
        val fix = RouteResearchLocation.bestLastKnown(context)
            ?.takeIf { it.ageMillis <= 6L * 60L * 60L * 1000L }
        val bounded = if (fix != null) {
            runCatching {
                geocoder.getFromLocationName(
                    query,
                    3,
                    (fix.point.latitude - 0.40).coerceAtLeast(-90.0),
                    (fix.point.longitude - 0.65).coerceAtLeast(-180.0),
                    (fix.point.latitude + 0.40).coerceAtMost(90.0),
                    (fix.point.longitude + 0.65).coerceAtMost(180.0),
                ).orEmpty()
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        if (bounded.isNotEmpty()) return bounded
        return runCatching { geocoder.getFromLocationName(query, 3).orEmpty() }.getOrDefault(emptyList())
    }

    private fun chooseResult(results: List<Address>, expectedHouse: String): Address? {
        if (results.isEmpty()) return null
        return results.firstOrNull { result ->
            val resultHouse = normalizeHouseNumber(result.subThoroughfare)
                ?: normalizeHouseNumber(result.featureName)
            resultHouse != null && sameHouse(resultHouse, expectedHouse)
        } ?: results.first()
    }

    private fun normalizeHouseNumber(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.matches(Regex("(?i)^[0-9]{1,4}[A-Za-z]?$")) }
        ?.uppercase(Locale.ROOT)

    private fun sameHouse(first: String, second: String): Boolean =
        first.trim().uppercase(Locale.ROOT) == second.trim().uppercase(Locale.ROOT)

    private fun distanceMeters(first: GeoIdentity, second: GeoIdentity): Double {
        val earthRadius = 6_371_000.0
        val lat1 = Math.toRadians(first.latitude)
        val lat2 = Math.toRadians(second.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(second.longitude - first.longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * earthRadius * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private fun readCache(context: Context, sourceKey: String): GeoIdentity? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(cacheSlot(sourceKey), null) ?: return null
        val fields = raw.split(FIELD_SEPARATOR, limit = 7)
        if (fields.size != 7 || fields[0] != sourceKey) return null
        return GeoIdentity(
            sourceKey = sourceKey,
            latitude = fields[1].toDoubleOrNull() ?: return null,
            longitude = fields[2].toDoubleOrNull() ?: return null,
            houseNumber = fields[3],
            canonicalDisplay = fields[4].takeIf(String::isNotBlank),
            resolvedAt = fields[5].toLongOrNull() ?: return null,
        )
    }

    private fun writeCache(context: Context, value: GeoIdentity) {
        val raw = listOf(
            value.sourceKey,
            value.latitude.toString(),
            value.longitude.toString(),
            value.houseNumber,
            value.canonicalDisplay.orEmpty(),
            value.resolvedAt.toString(),
            "v1",
        ).joinToString(FIELD_SEPARATOR)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(cacheSlot(value.sourceKey), raw)
            .apply()
    }

    private fun cacheSlot(sourceKey: String): String =
        CACHE_PREFIX + sourceKey.hashCode().toUInt().toString(16)
}
