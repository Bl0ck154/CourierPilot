package com.block154.courierpilot

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import java.text.Normalizer
import java.util.Locale

/**
 * Parser fixes should also repair statistics for offers captured by older versions. This migration
 * is intentionally data-only: it does not change the SQLite schema and runs once per repair
 * revision.
 */
internal object OfferDataRepair {
    private const val PREFS = "courier_offer_repairs"
    private const val KEY_REVISION = "parser_repair_revision"
    private const val CURRENT_REVISION = 3
    private const val EXACT_DUPLICATE_WINDOW_MS = 2L * 60L * 1000L
    private const val BURST_DUPLICATE_WINDOW_MS = 90L * 1000L
    private const val LIST_SEPARATOR = "\u001F"

    fun runIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_REVISION, 0) >= CURRENT_REVISION) return

        val database = OfferDatabase.get(appContext)
        val sqlite = database.writableDatabase
        val records = database.recordsSince(0L, 5000).sortedBy { it.capturedAt }
        val latestByExactFingerprint = mutableMapOf<String, OfferRecord>()
        val latestByBurstFingerprint = mutableMapOf<String, OfferRecord>()
        val duplicateScreenshotUris = mutableListOf<String>()

        sqlite.beginTransaction()
        try {
            records.forEach { original ->
                val parsed = OfferParser.parse(original.rawText)
                val repaired = original.withCurrentParsedStructure()
                val values = ContentValues().apply {
                    put("price_cents", repaired.priceCents)
                    repaired.distanceMeters?.let { put("distance_meters", it) } ?: putNull("distance_meters")
                    repaired.restaurant?.let { put("restaurant", it) } ?: putNull("restaurant")
                    put("merchant_names", encodeList(repaired.merchantNames))
                    put("pickup_addresses", encodeList(repaired.pickupAddresses))
                    put("customer_names", encodeList(repaired.customerNames))
                    put("dropoff_addresses", encodeList(repaired.dropoffAddresses))
                    repaired.deliveryCount?.let { put("delivery_count", it) } ?: putNull("delivery_count")
                    repaired.estimatedMinutesMin?.let { put("estimated_min", it) } ?: putNull("estimated_min")
                    repaired.estimatedMinutesMax?.let { put("estimated_max", it) } ?: putNull("estimated_max")
                }
                sqlite.update("offers", values, "id = ?", arrayOf(original.id.toString()))

                if (!CourierSignals.hasStrongOfferIdentity(parsed, original.rawText)) return@forEach

                val exactFingerprint = CourierSignals.offerFingerprint(original.packageName, parsed, original.rawText)
                val burstFingerprint = burstFingerprint(repaired)
                val previousExact = latestByExactFingerprint[exactFingerprint]
                val previousBurst = latestByBurstFingerprint[burstFingerprint]

                val exactDuplicate = previousExact != null &&
                    original.capturedAt >= previousExact.capturedAt &&
                    original.capturedAt - previousExact.capturedAt <= EXACT_DUPLICATE_WINDOW_MS
                val burstDuplicate = previousBurst != null &&
                    original.capturedAt >= previousBurst.capturedAt &&
                    original.capturedAt - previousBurst.capturedAt <= BURST_DUPLICATE_WINDOW_MS

                if (exactDuplicate || burstDuplicate) {
                    sqlite.delete("offers", "id = ?", arrayOf(original.id.toString()))
                    duplicateScreenshotUris += original.screenshotUri
                } else {
                    latestByExactFingerprint[exactFingerprint] = original
                    latestByBurstFingerprint[burstFingerprint] = original
                }
            }
            sqlite.setTransactionSuccessful()
            prefs.edit().putInt(KEY_REVISION, CURRENT_REVISION).apply()
        } finally {
            sqlite.endTransaction()
        }

        // MediaStore is outside the SQLite transaction. Cleanup is best-effort: history/statistics
        // are already correct even if an OEM refuses deletion of an old screenshot URI.
        duplicateScreenshotUris.distinct().forEach { uri ->
            runCatching { appContext.contentResolver.delete(Uri.parse(uri), null, null) }
        }
    }

    /**
     * Short-lived burst identity for frames from one live offer. Accessibility can reveal extra
     * customer/address rows between callbacks, making the full semantic fingerprint change even
     * though price + route distance + delivery count + venue summary still identify the same offer.
     */
    internal fun burstFingerprint(record: OfferRecord): String {
        val merchants = (record.merchantNames.ifEmpty { listOfNotNull(record.restaurant) })
            .map(::identityToken)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
            .joinToString(";")
        return buildString {
            append(record.packageName)
            append("|p=").append(record.priceCents)
            append("|d=").append(record.distanceMeters ?: -1)
            append("|n=").append(record.deliveryCount ?: -1)
            append("|m=").append(merchants)
        }
    }

    private fun identityToken(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun encodeList(values: List<String>): String? =
        values.map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(LIST_SEPARATOR)
}
