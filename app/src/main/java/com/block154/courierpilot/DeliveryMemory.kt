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

        val platform = OfferState.platformLabel(packageName)
        val database = CourierMetaDatabase.get(context)
        val observations = CourierSignals.extractAccessCodeObservations(text, fallback)

        if (observations.isNotEmpty()) {
            // The current delivery already exposes a code, so there is no need to suggest an old one.
            AccessCodeSuggestions.clear(context)
            observations.forEach { observation ->
                runCatching { database.saveAccessCode(observation, platform) }
                    .onSuccess {
                        CaptureEventLog.append(
                            context,
                            stage = "access_code",
                            platform = platform,
                            message = "Building access code learned locally",
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
            return
        }

        // No code is present on the current screen. If we recognize the same building from a
        // previous delivery, expose the known code(s) inside CourierPilot for one-tap copying.
        val candidates = buildList {
            addAll(addresses.asReversed())
            previous?.let(::add)
        }
        var matched = false
        for (address in candidates.distinct()) {
            val normalized = CourierSignals.normalizeBuildingAddress(address) ?: continue
            val known = database.codesForBuilding(normalized.first)
            if (known.isEmpty()) continue
            AccessCodeSuggestions.save(
                context,
                AccessCodeSuggestion(
                    displayAddress = normalized.second,
                    codes = known.map { it.code }.distinct(),
                    platform = platform,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            CaptureEventLog.append(
                context,
                stage = "access_code_match",
                platform = platform,
                message = "Known building access code matched locally",
                dedupeWindowMs = 30_000L,
            )
            matched = true
            break
        }
        if (!matched && addresses.isNotEmpty()) AccessCodeSuggestions.clear(context)
    }

    private fun addressKey(packageName: String): String = "last_address_${packageName.replace('.', '_')}"
}
