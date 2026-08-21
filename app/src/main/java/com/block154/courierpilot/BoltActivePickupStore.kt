package com.block154.courierpilot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable local fallback for Bolt add-on offers.
 *
 * Bolt may fully cover an already-active pickup pin with another marker. Pixels cannot recover a
 * marker that is no longer visible, so before a new Bolt offer replaces the lifecycle pointer we
 * remember pickup addresses from the previously accepted-but-not-yet-picked-up offer. These
 * addresses are geocoded normally and participate in routing even when their map pins are hidden.
 */
internal object BoltActivePickupStore {
    private const val PREFS = "courierpilot_bolt_active_pickups"
    private const val KEY_ENTRIES = "entries"
    private const val TTL_MS = 3L * 60L * 60L * 1000L
    private const val MAX_ENTRIES = 8

    private data class Entry(val address: String, val addedAt: Long)

    fun rememberUncollectedTask(context: Context, task: DeliveryLifecycleTask?) {
        val active = task?.takeIf {
            it.state == DeliveryEventType.ACCEPTED || it.state == DeliveryEventType.ARRIVED_PICKUP
        } ?: return
        val record = runCatching { OfferDatabase.get(context).findById(active.offerId) }.getOrNull() ?: return
        if (record.packageName != CourierSignals.BOLT_PACKAGE) return
        rememberAddresses(context, record.pickupAddresses)
    }

    fun supplementalForOffer(
        context: Context,
        currentPickupAddresses: List<String>,
        now: Long = System.currentTimeMillis(),
    ): List<String> {
        val currentKeys = currentPickupAddresses.mapNotNull(::addressKey).toSet()
        val fresh = load(context, now)
        return fresh
            .filter { entry -> addressKey(entry.address)?.let { it !in currentKeys } ?: true }
            .map { it.address }
    }

    fun markPickedUp(context: Context, stopKey: String?) {
        val key = stopKey?.trim()?.takeIf(String::isNotEmpty) ?: return
        val now = System.currentTimeMillis()
        val remaining = load(context, now).filterNot { entry ->
            lifecycleKey(entry.address) == key || addressKey(entry.address) == key
        }
        save(context, remaining)
    }

    internal fun rememberAddresses(
        context: Context,
        addresses: List<String>,
        now: Long = System.currentTimeMillis(),
    ) {
        if (addresses.isEmpty()) return
        val merged = load(context, now).toMutableList()
        for (address in addresses.filter(String::isNotBlank)) {
            val key = addressKey(address) ?: continue
            merged.removeAll { addressKey(it.address) == key }
            merged += Entry(address.trim(), now)
        }
        save(context, merged.takeLast(MAX_ENTRIES))
    }

    internal fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun load(context: Context, now: Long): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENTRIES, null)
            ?: return emptyList()
        val parsedWithCount = runCatching {
            val array = JSONArray(raw)
            val entries = mutableListOf<Entry>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val address = item.optString("address").trim()
                val addedAt = item.optLong("addedAt", -1L)
                if (address.isBlank() || addedAt < 0L) continue
                if (now - addedAt !in 0..TTL_MS) continue
                entries += Entry(address, addedAt)
            }
            entries.toList() to array.length()
        }.getOrElse { emptyList<Entry>() to 0 }
        val parsed = parsedWithCount.first
        if (parsed.size != parsedWithCount.second) save(context, parsed)
        return parsed
    }

    private fun save(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().put("address", entry.address).put("addedAt", entry.addedAt))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, array.toString())
            .apply()
    }

    private fun lifecycleKey(address: String): String? =
        CourierSignals.normalizeBuildingAddress(address)?.first

    private fun addressKey(address: String): String? =
        DeliveryAddressNormalizer.identity(address)?.key
            ?: address.trim().lowercase().replace(Regex("\\s+"), " ").takeIf(String::isNotEmpty)
}
