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
    // Revision 18 also removes Wolt boost/promo card metadata from persisted merchant identity and
    // re-runs the current route parser so old 0.15.58 rows can recover the real venue/drop-off from
    // their stored raw card text.
    private const val CURRENT_REVISION = 18
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
                if (shouldDiscardUntrustedWoltCapture(original) || shouldDiscardWoltNavigationGhost(original)) {
                    deleteDuplicateRow(sqlite, original.id)
                    original.screenshotUri.takeIf(String::isNotBlank)?.let(duplicateScreenshotUris::add)
                    return@forEach
                }

                val rawParsed = original.rawText.takeIf(String::isNotBlank)?.let(OfferParser::parse)
                val trustedIncrementalMoney = trustedHistoricalIncrementalMoney(original, rawParsed)
                val reparsed = if (trustedIncrementalMoney != null && rawParsed != null) {
                    // The old persisted arrays for this exact offer family are known to be unsafe:
                    // 0.15.58 could promote a customer stop to pickup and keep the old destination
                    // as the only drop-off. The current parser is anchored to explicit Wolt labels,
                    // so use its route structure directly instead of preferring the richer bad list.
                    original.copy(
                        priceCents = trustedIncrementalMoney.amountMinor.toInt(),
                        currencyCode = trustedIncrementalMoney.currencyCode,
                        currencyFractionDigits = trustedIncrementalMoney.fractionDigits,
                        distanceMeters = rawParsed.distanceMeters ?: original.distanceMeters,
                        restaurant = rawParsed.restaurant ?: original.restaurant,
                        merchantNames = rawParsed.merchantNames.ifEmpty { original.merchantNames },
                        pickupAddresses = rawParsed.pickupAddresses.ifEmpty { original.pickupAddresses },
                        customerNames = rawParsed.customerNames.ifEmpty { original.customerNames },
                        dropoffAddresses = rawParsed.dropoffAddresses.ifEmpty { original.dropoffAddresses },
                        deliveryCount = rawParsed.deliveryCount ?: original.deliveryCount,
                        estimatedMinutesMin = rawParsed.estimatedMinutesMin ?: original.estimatedMinutesMin,
                        estimatedMinutesMax = rawParsed.estimatedMinutesMax ?: original.estimatedMinutesMax,
                    )
                } else {
                    original.withCurrentParsedStructure()
                }
                val visualFingerprint = original.visualFingerprint.ifBlank { visualBackfills[original.id].orEmpty() }
                val repairedIncrementalMoney = trustedIncrementalMoney != null &&
                    (reparsed.priceCents != original.priceCents ||
                        reparsed.currencyCode != original.currencyCode ||
                        reparsed.currencyFractionDigits != original.currencyFractionDigits)
                val incrementalFullRouteIsUntrusted = rawParsed?.isIncrementalOffer == true &&
                    original.marketRouteDistanceMeters != null
                val rejectedHistoricalRoute = incrementalFullRouteIsUntrusted ||
                    OfferRouteDistancePolicy.calculatedRouteWasRejected(
                        reparsed.distanceMeters,
                        original.marketRouteDistanceMeters,
                    )
                val repaired = reparsed.copy(
                    visualFingerprint = visualFingerprint,
                    marketRouteDistanceMeters = if (rejectedHistoricalRoute) null else original.marketRouteDistanceMeters,
                    marketRouteSource = if (rejectedHistoricalRoute) "" else original.marketRouteSource,
                )
                val values = ContentValues().apply {
                    put("price_cents", repaired.priceCents)
                    put("currency_code", repaired.currencyCode)
                    put("currency_fraction_digits", repaired.currencyFractionDigits)
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
                    repaired.marketRouteDistanceMeters?.let { put("market_route_distance_meters", it) } ?: putNull("market_route_distance_meters")
                    repaired.marketRouteSource.takeIf(String::isNotBlank)?.let { put("market_route_source", it) } ?: putNull("market_route_source")
                }
                sqlite.update("offers", values, "id = ?", arrayOf(original.id.toString()))
                if (rejectedHistoricalRoute || repairedIncrementalMoney) {
                    sqlite.delete("market_observations", "offer_id = ?", arrayOf(original.id.toString()))
                }

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


    internal fun shouldDiscardUntrustedWoltCapture(record: OfferRecord): Boolean {
        if (record.packageName != CourierSignals.WOLT_PACKAGE || record.rawText.isBlank()) return false

        // Real-device telemetry captured "MIR" when OCR misread Wolt's minute label (MIN) as an ISO
        // currency. Wolt never pays in this UI token, so these rows are safe to remove.
        if (record.currencyCode.equals("MIR", ignoreCase = true) ||
            record.currencyCode.equals("MIN", ignoreCase = true)
        ) return true

        val rawParsed = OfferParser.parse(record.rawText)
        if (rawParsed.money != null) return false
        if (!record.rawText.contains("expected earnings for the full delivery", ignoreCase = true)) return false

        // Keep historical records that still have a meaningful venue or destination even if an old
        // Accessibility ordering no longer reproduces the amount. The bad €28 capture had neither:
        // it was just an unrelated full-screen OCR amount saved while Wolt was still loading.
        val structural = record.withCurrentParsedStructure()
        val hasMerchant = !structural.restaurant.isNullOrBlank() || structural.merchantNames.isNotEmpty()
        val hasRoute = structural.pickupAddresses.isNotEmpty() || structural.dropoffAddresses.isNotEmpty() ||
            structural.distanceMeters != null
        return !hasMerchant && !hasRoute
    }

    internal fun shouldDiscardWoltNavigationGhost(record: OfferRecord): Boolean {
        if (record.packageName != CourierSignals.WOLT_PACKAGE) return false
        // The bad 0.15.47 captures were armed by screen discovery after the real offer had ended.
        // Notification-backed rows are deliberately excluded from this repair.
        if (!record.captureKey.startsWith("screen:")) return false
        if (record.rawText.isBlank()) return false
        return CourierSignals.looksLikeWoltNonOfferNavigationScreen(record.packageName, record.rawText)
    }

    /**
     * Pre-0.15.59 Wolt add-on captures could persist a postcode-derived amount even though the raw
     * card still contained an explicit `+€x.xx` line. Only repair money when the current parser
     * recognizes the card as incremental and that explicit plus-prefixed money line agrees with
     * the parser's anchored result. Ordinary historical offers keep capture-time money immutable.
     */
    internal fun trustedHistoricalIncrementalMoney(record: OfferRecord, parsed: ParsedOffer?): MoneyAmount? {
        if (record.packageName != CourierSignals.WOLT_PACKAGE || parsed?.isIncrementalOffer != true) return null
        val money = parsed.money ?: return null
        if (money.amountMinor !in 1L..Int.MAX_VALUE.toLong()) return null
        val hasExplicitIncrementalMoney = record.rawText.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .any { line ->
                line.startsWith("+") && MarketCurrencyParser.parse(line) == money
            }
        return money.takeIf { hasExplicitIncrementalMoney }
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
        var previousBolt: OfferRecord? = null
        records.forEach { record ->
            if (record.packageName != CourierSignals.BOLT_PACKAGE || record.screenshotUri.isBlank()) return@forEach

            val samePricePrevious = lastByPrice[record.priceCents]
            if (samePricePrevious != null &&
                record.capturedAt - samePricePrevious.capturedAt in 0L..OfferDedupeIdentity.BURST_WINDOW_MS
            ) {
                ids += samePricePrevious.id
                ids += record.id
            }

            val nearbyDifferentPrice = previousBolt
            if (nearbyDifferentPrice != null &&
                nearbyDifferentPrice.priceCents != record.priceCents &&
                record.capturedAt - nearbyDifferentPrice.capturedAt in 0L..OfferDedupeIdentity.BOLT_PRICE_DRIFT_WINDOW_MS
            ) {
                ids += nearbyDifferentPrice.id
                ids += record.id
            }

            lastByPrice[record.priceCents] = record
            previousBolt = record
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
