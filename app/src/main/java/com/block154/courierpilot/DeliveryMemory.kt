package com.block154.courierpilot

import android.content.Context
import android.widget.Toast

/**
 * Learns local delivery context for buildings seen in the courier apps.
 *
 * Address memory is deliberately loss-tolerant: normalized addresses power lookup/search while a
 * nearby text excerpt plus the raw Accessibility/OCR screen are retained for future parsers.
 */
internal object DeliveryMemory {
    private const val PREFS = "courierpilot_delivery_memory"

    fun observeScreen(context: Context, packageName: String, text: String) {
        if (text.isBlank()) return

        LiveAdvisorHub.attach(context)
        if (OfferState.pending(context) != null) LiveAdvisorHub.hideForCapture(context)
        LiveAdvisorHub.observeScreen(context, packageName, text)

        val parsed = OfferParser.parse(text)
        if (CourierSignals.looksLikeOfferScreen(text, parsed)) {
            CourierPresence.markOfferOnline(context, packageName, "offer screen")
        }

        val detectedAddresses = CourierSignals.likelyAddresses(text)
        val allAddresses = (detectedAddresses + parsed.pickupAddresses + parsed.dropoffAddresses)
            .mapNotNull { DeliveryAddressNormalizer.normalize(it) }
            .distinctBy { it.first }
            .map { it.second }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = addressKey(packageName)
        val previous = prefs.getString(key, null)
        val fallback = detectedAddresses.lastOrNull() ?: previous
        if (detectedAddresses.isNotEmpty()) prefs.edit().putString(key, detectedAddresses.last()).apply()

        val platform = OfferState.platformLabel(packageName)
        val database = CourierMetaDatabase.get(context)

        // Store every canonical building exposed by the current courier screen. Apartment suffixes
        // are removed before SQLite sees them, so 1, 1-36 and 1–36 share one building row.
        allAddresses.forEach { address ->
            val customer = customerForAddress(parsed, address)
            val merchant = merchantForAddress(parsed, address)
            runCatching {
                val addressId = database.saveAddressObservation(
                    address = address,
                    platform = platform,
                    customerName = customer,
                    detailsText = addressContext(text, address),
                    rawText = text,
                )
                if (addressId != null) {
                    merchant?.let {
                        database.saveAddressEntity(
                            addressId = addressId,
                            entityType = CourierMetaDatabase.ENTITY_VENUE,
                            name = it,
                            platform = platform,
                        )
                    }
                    customer?.let {
                        database.saveAddressEntity(
                            addressId = addressId,
                            entityType = CourierMetaDatabase.ENTITY_CUSTOMER,
                            name = it,
                            platform = platform,
                        )
                    }
                }
            }.onFailure {
                CaptureEventLog.append(
                    context,
                    stage = "address_memory_failed",
                    platform = platform,
                    message = it.javaClass.simpleName,
                    dedupeWindowMs = 30_000L,
                )
            }
        }

        val observations = CourierSignals.extractAccessCodeObservations(text, fallback)
            .mapNotNull { observation ->
                DeliveryAddressNormalizer.normalize(observation.displayAddress)?.let { normalized ->
                    AccessCodeObservation(
                        buildingKey = normalized.first,
                        displayAddress = normalized.second,
                        code = observation.code,
                    )
                }
            }
            .distinctBy { "${it.buildingKey}|${it.code}" }
        if (observations.isNotEmpty()) {
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

        // Suggest historical codes only when the current screen itself exposes an address. The
        // remembered fallback is used for learning across split screens, never for a blind suggestion.
        var matched = false
        for (address in detectedAddresses.asReversed().distinct()) {
            val normalized = DeliveryAddressNormalizer.normalize(address) ?: continue
            val known = database.codesForBuilding(normalized.first)
            if (known.isEmpty()) continue
            val codes = known.map { it.code }.distinct()
            val oldSuggestion = AccessCodeSuggestions.latest(context)
            val sameSuggestion = oldSuggestion?.displayAddress == normalized.second && oldSuggestion.codes == codes
            val suggestion = AccessCodeSuggestion(
                displayAddress = normalized.second,
                codes = codes,
                platform = platform,
                updatedAt = System.currentTimeMillis(),
            )
            if (!sameSuggestion) {
                AccessCodeSuggestions.save(context, suggestion)
                Toast.makeText(
                    context,
                    "Possible door code · ${normalized.second}: ${codes.joinToString(" / ")}",
                    Toast.LENGTH_LONG,
                ).show()
                CaptureEventLog.append(
                    context,
                    stage = "access_code_match",
                    platform = platform,
                    message = "Possible historical building access code matched locally",
                    dedupeWindowMs = 30_000L,
                )
            }
            matched = true
            break
        }
        if (!matched && detectedAddresses.isNotEmpty()) AccessCodeSuggestions.clear(context)
    }

    private fun merchantForAddress(parsed: ParsedOffer, address: String): String? {
        val key = DeliveryAddressNormalizer.key(address) ?: return null
        val index = parsed.pickupAddresses.indexOfFirst {
            DeliveryAddressNormalizer.key(it) == key
        }
        return parsed.merchantNames.getOrNull(index)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: parsed.restaurant?.trim()?.takeIf(String::isNotEmpty)?.takeIf { parsed.pickupAddresses.size <= 1 }
    }

    private fun customerForAddress(parsed: ParsedOffer, address: String): String? {
        val key = DeliveryAddressNormalizer.key(address) ?: return null
        val index = parsed.dropoffAddresses.indexOfFirst {
            DeliveryAddressNormalizer.key(it) == key
        }
        return parsed.customerNames.getOrNull(index)
            ?.takeUnless { it.equals("Customer", ignoreCase = true) }
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    private fun addressContext(text: String, address: String): String? {
        val targetKey = DeliveryAddressNormalizer.key(address) ?: return null
        val lines = text.lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter(String::isNotEmpty)
            .toList()
        val index = lines.indexOfFirst { line ->
            DeliveryAddressNormalizer.key(line) == targetKey
        }
        if (index < 0) return null
        val from = (index - 2).coerceAtLeast(0)
        val to = (index + 10).coerceAtMost(lines.size)
        return lines.subList(from, to).joinToString("\n").take(4_000)
    }

    private fun addressKey(packageName: String): String = "last_address_${packageName.replace('.', '_')}"
}
