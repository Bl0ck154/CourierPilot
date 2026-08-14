package com.block154.courierpilot

import android.content.Context

/**
 * Learns only the minimum useful delivery memory: building address + access code.
 * Customer names and raw delivery instructions are intentionally not copied into this database.
 */
internal object DeliveryMemory {
    private const val PREFS = "courierpilot_delivery_memory"

    fun observeScreen(context: Context, packageName: String, text: String) {
        if (text.isBlank()) return
        val addresses = CourierSignals.likelyAddresses(text)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = addressKey(packageName)
        val previous = prefs.getString(key, null)
        val fallback = addresses.lastOrNull() ?: previous
        if (addresses.isNotEmpty()) prefs.edit().putString(key, addresses.last()).apply()

        val observations = CourierSignals.extractAccessCodeObservations(text, fallback)
        if (observations.isEmpty()) return
        val platform = OfferState.platformLabel(packageName)
        val database = CourierMetaDatabase.get(context)
        observations.forEach { observation ->
            runCatching { database.saveAccessCode(observation, platform) }
                .onSuccess {
                    CaptureEventLog.append(
                        context,
                        stage = "access_code",
                        platform = platform,
                        message = "Building access code learned for ${observation.displayAddress}",
                        dedupeWindowMs = 30_000L,
                    )
                }
                .onFailure {
                    CaptureEventLog.append(
                        context,
                        stage = "access_code_failed",
                        platform = platform,
                        message = it.javaClass.simpleName,
                        dedupeWindowMs = 30_000L,
                    )
                }
        }
    }

    private fun addressKey(packageName: String): String = "last_address_${packageName.replace('.', '_')}"
}
