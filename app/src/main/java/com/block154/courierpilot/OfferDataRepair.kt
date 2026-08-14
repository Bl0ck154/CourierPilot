package com.block154.courierpilot

import android.content.ContentValues
import android.content.Context

/**
 * Parser fixes should also repair statistics for offers captured by older versions. This migration
 * is intentionally data-only: it does not change the SQLite schema and runs once per parser repair
 * revision.
 */
internal object OfferDataRepair {
    private const val PREFS = "courier_offer_repairs"
    private const val KEY_REVISION = "parser_repair_revision"
    private const val CURRENT_REVISION = 1

    fun runIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_REVISION, 0) >= CURRENT_REVISION) return

        val database = OfferDatabase.get(appContext)
        val sqlite = database.writableDatabase
        val records = database.recordsSince(0L, 5000)
        sqlite.beginTransaction()
        try {
            records.forEach { original ->
                val repaired = original.withCurrentParsedStructure()
                val values = ContentValues()
                if (repaired.priceCents != original.priceCents) {
                    values.put("price_cents", repaired.priceCents)
                }
                if (repaired.deliveryCount != original.deliveryCount) {
                    repaired.deliveryCount?.let { values.put("delivery_count", it) }
                        ?: values.putNull("delivery_count")
                }
                if (values.size() > 0) {
                    sqlite.update("offers", values, "id = ?", arrayOf(original.id.toString()))
                }
            }
            sqlite.setTransactionSuccessful()
            prefs.edit().putInt(KEY_REVISION, CURRENT_REVISION).apply()
        } finally {
            sqlite.endTransaction()
        }
    }
}
