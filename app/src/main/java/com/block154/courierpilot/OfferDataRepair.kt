package com.block154.courierpilot

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * Repairs parser output and removes historical duplicate captures created by older pipeline versions.
 * This is data-only and idempotent per revision.
 */
internal object OfferDataRepair {
    private const val PREFS = "courier_offer_repairs"
    private const val KEY_REVISION = "parser_repair_revision"
    // Revision 9 also backfills a visual fingerprint from already-saved Bolt screenshots. This lets
    // the repair remove historical double-captures even when OCR parsed different distances/text.
    private const val CURRENT_REVISION = 9
    private const val LIST_SEPARATOR = "\u001F"

    fun runIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_REVISION, 0) >= CURRENT_REVISION) return

        val database = OfferDatabase.get(appContext)
        val sqlite = database.writableDatabase
        val records = database.recordsSince(0L, 5000).sortedBy { it.capturedAt }
        val visualBackfillIds = suspiciousBoltVisualCandidates(records)
        val recentSurvivors = ArrayDeque<OfferRecord>()
        val duplicateScreenshotUris = mutableListOf<String>()

        sqlite.beginTransaction()
        try {
            records.forEach { original ->
                val reparsed = original.withCurrentParsedStructure()
                val visualFingerprint = when {
                    original.visualFingerprint.isNotBlank() -> original.visualFingerprint
                    original.id in visualBackfillIds && original.screenshotUri.isNotBlank() ->
                        readVisualFingerprint(appContext, original.screenshotUri).orEmpty()
                    else -> ""
                }
                val repaired = reparsed.copy(visualFingerprint = visualFingerprint)
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
                    repaired.visualFingerprint.takeIf(String::isNotBlank)?.let { put("visual_fingerprint", it) } ?: putNull("visual_fingerprint")
                }
                sqlite.update("offers", values, "id = ?", arrayOf(original.id.toString()))

                while (recentSurvivors.isNotEmpty() &&
                    repaired.capturedAt - recentSurvivors.first().capturedAt > OfferDedupeIdentity.PERSIST_DEDUPE_WINDOW_MS
                ) {
                    recentSurvivors.removeFirst()
                }

                val duplicate = recentSurvivors.any { previous ->
                    OfferDedupeIdentity.isSameLiveOffer(previous, repaired)
                }
                if (duplicate) {
                    sqlite.delete("offers", "id = ?", arrayOf(original.id.toString()))
                    if (original.screenshotUri.isNotBlank()) duplicateScreenshotUris += original.screenshotUri
                } else {
                    recentSurvivors.addLast(repaired)
                }
            }
            sqlite.setTransactionSuccessful()
            prefs.edit().putInt(KEY_REVISION, CURRENT_REVISION).apply()
        } finally {
            sqlite.endTransaction()
        }

        // MediaStore is outside the SQLite transaction. Cleanup is best-effort: history/statistics
        // are already repaired even if the OEM refuses deletion of an old screenshot URI.
        duplicateScreenshotUris.distinct().forEach { uri ->
            runCatching { appContext.contentResolver.delete(Uri.parse(uri), null, null) }
        }
    }


    private fun suspiciousBoltVisualCandidates(records: List<OfferRecord>): Set<Long> {
        val ids = mutableSetOf<Long>()
        val lastByPrice = mutableMapOf<Int, OfferRecord>()
        records.forEach { record ->
            if (record.packageName != CourierSignals.BOLT_PACKAGE || record.screenshotUri.isBlank()) return@forEach
            val previous = lastByPrice[record.priceCents]
            if (previous != null &&
                record.capturedAt - previous.capturedAt in 0L..OfferDedupeIdentity.BURST_WINDOW_MS
            ) {
                ids += previous.id
                ids += record.id
            }
            lastByPrice[record.priceCents] = record
        }
        return ids
    }

    private fun readVisualFingerprint(context: Context, screenshotUri: String): String? {
        val bitmap = runCatching {
            context.contentResolver.openInputStream(Uri.parse(screenshotUri))?.use(BitmapFactory::decodeStream)
        }.getOrNull() ?: return null
        return try {
            OfferVisualFingerprint.fromBottomCard(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun encodeList(values: List<String>): String? =
        values.map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(LIST_SEPARATOR)
}
