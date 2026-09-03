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
    // Revision 11 removes short Wolt same-route recaptures even when one transient frame stored a
    // different price, keeps the richer/credible row, and removes orphan market observations too.
    private const val CURRENT_REVISION = 11
    private const val LIST_SEPARATOR = "\u001F"

    @Synchronized
    fun runIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_REVISION, 0) >= CURRENT_REVISION) return

        val database = OfferDatabase.get(appContext)
        val sqlite = database.writableDatabase
        val records = database.recordsSince(0L, 5000).sortedBy { it.capturedAt }
        val visualBackfillIds = suspiciousBoltVisualCandidates(records)
        // Decode old PNGs before opening the SQLite write transaction. Image I/O can take seconds on
        // a large history and must never hold the database lock while the dashboard is drawing.
        val visualBackfills = records.asSequence()
            .filter { it.id in visualBackfillIds && it.visualFingerprint.isBlank() && it.screenshotUri.isNotBlank() }
            .mapNotNull { record -> readVisualFingerprint(appContext, record.screenshotUri)?.let { record.id to it } }
            .toMap()
        val recentSurvivors = mutableListOf<OfferRecord>()
        val duplicateScreenshotUris = mutableListOf<String>()

        sqlite.beginTransaction()
        try {
            records.forEach { original ->
                val reparsed = original.withCurrentParsedStructure()
                val visualFingerprint = original.visualFingerprint.ifBlank { visualBackfills[original.id].orEmpty() }
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

                recentSurvivors.removeAll { previous ->
                    repaired.capturedAt - previous.capturedAt > OfferDedupeIdentity.PERSIST_DEDUPE_WINDOW_MS
                }

                val matches = recentSurvivors.filter { previous ->
                    OfferDedupeIdentity.isSameLiveOffer(previous, repaired)
                }
                if (matches.isEmpty()) {
                    recentSurvivors += repaired
                    return@forEach
                }

                val winner = (matches + repaired).reduce { best, candidate ->
                    OfferDedupeIdentity.preferredHistoricalRecord(best, candidate)
                }

                matches.filter { it.id != winner.id }.forEach { duplicate ->
                    deleteDuplicateRow(sqlite, duplicate.id)
                    recentSurvivors.removeAll { it.id == duplicate.id }
                    duplicate.screenshotUri.takeIf(String::isNotBlank)?.let(duplicateScreenshotUris::add)
                }

                if (winner.id == repaired.id) {
                    recentSurvivors += repaired
                } else {
                    deleteDuplicateRow(sqlite, repaired.id)
                    repaired.screenshotUri.takeIf(String::isNotBlank)?.let(duplicateScreenshotUris::add)
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

    private fun deleteDuplicateRow(sqlite: android.database.sqlite.SQLiteDatabase, offerId: Long) {
        // market_observations has no FK cascade; remove the derived sample explicitly so a deleted
        // bad capture cannot keep influencing Market scoring.
        sqlite.delete("market_observations", "offer_id = ?", arrayOf(offerId.toString()))
        sqlite.delete("offers", "id = ?", arrayOf(offerId.toString()))
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
