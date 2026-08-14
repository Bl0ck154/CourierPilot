package com.block154.courierpilot

import android.content.Context
import java.util.Locale

internal data class DeliveryLifecycleEvidence(val type: DeliveryEventType, val cue: String)

/**
 * Conservative delivery outcome tracker. It records only explicit textual state cues from the
 * courier UI and never infers acceptance/completion merely because an offer screen disappeared.
 */
internal object DeliveryLifecycleTracking {
    private const val PREFS = "courierpilot_delivery_lifecycle"
    private const val REPEAT_SUPPRESSION_MS = 20L * 60L * 1000L

    fun onOfferCaptured(context: Context, packageName: String, offerId: Long, capturedAt: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = keyPrefix(packageName)
        prefs.edit()
            .putLong("${prefix}_offer", offerId)
            .putString("${prefix}_last_event", DeliveryEventType.OFFER_CAPTURED.name)
            .putLong("${prefix}_last_event_at", capturedAt)
            .apply()
        record(
            context,
            offerId,
            DeliveryTimelineEvent(
                type = DeliveryEventType.OFFER_CAPTURED,
                timestampMillis = capturedAt,
                source = "priced_offer_persisted",
                confidence = 1.0,
            ),
        )
    }

    fun observeScreen(context: Context, packageName: String, text: String) {
        if (text.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = keyPrefix(packageName)
        val offerId = prefs.getLong("${prefix}_offer", -1L).takeIf { it > 0 } ?: return
        val evidence = detect(text) ?: return
        val now = System.currentTimeMillis()
        val lastType = prefs.getString("${prefix}_last_event", null)
            ?.let { runCatching { DeliveryEventType.valueOf(it) }.getOrNull() }
            ?: DeliveryEventType.OFFER_CAPTURED
        val lastAt = prefs.getLong("${prefix}_last_event_at", 0L)
        if (lastType == evidence.type && now - lastAt < REPEAT_SUPPRESSION_MS) return
        if (!canAdvance(lastType, evidence.type)) return

        val stopKey = stopKey(text, evidence.type)
        record(
            context,
            offerId,
            DeliveryTimelineEvent(
                type = evidence.type,
                timestampMillis = now,
                stopKey = stopKey,
                source = "accessibility_explicit:${evidence.cue}",
                confidence = 1.0,
            ),
        )
        prefs.edit()
            .putString("${prefix}_last_event", evidence.type.name)
            .putLong("${prefix}_last_event_at", now)
            .apply()

        if (evidence.type == DeliveryEventType.DELIVERED || evidence.type == DeliveryEventType.CANCELLED) {
            prefs.edit().remove("${prefix}_offer").apply()
        }
    }

    internal fun canAdvance(from: DeliveryEventType, to: DeliveryEventType): Boolean = when (from) {
        DeliveryEventType.OFFER_CAPTURED -> to in setOf(DeliveryEventType.ACCEPTED, DeliveryEventType.CANCELLED)
        DeliveryEventType.ACCEPTED -> to in setOf(
            DeliveryEventType.ARRIVED_PICKUP,
            DeliveryEventType.PICKED_UP,
            DeliveryEventType.CANCELLED,
        )
        DeliveryEventType.ARRIVED_PICKUP -> to in setOf(DeliveryEventType.PICKED_UP, DeliveryEventType.CANCELLED)
        DeliveryEventType.PICKED_UP -> to in setOf(
            DeliveryEventType.ARRIVED_DROPOFF,
            DeliveryEventType.DELIVERED,
            DeliveryEventType.CANCELLED,
        )
        DeliveryEventType.ARRIVED_DROPOFF -> to in setOf(DeliveryEventType.DELIVERED, DeliveryEventType.CANCELLED)
        DeliveryEventType.DELIVERED, DeliveryEventType.CANCELLED -> false
    }

    internal fun detect(text: String): DeliveryLifecycleEvidence? {
        val lower = text.lowercase(Locale.ROOT).replace('’', '\'')
        val ordered = listOf(
            DeliveryEventType.CANCELLED to listOf(
                "order cancelled", "order canceled", "delivery cancelled", "delivery canceled", "task cancelled", "task canceled",
                "užsakymas atšauktas", "uzsakymas atsauktas", "заказ отменен", "заказ отменён", "замовлення скасовано",
            ),
            DeliveryEventType.DELIVERED to listOf(
                "delivery completed", "delivery complete", "order completed", "delivered successfully",
                "pristatymas baigtas", "užsakymas pristatytas", "uzsakymas pristatytas", "заказ доставлен", "замовлення доставлено",
            ),
            DeliveryEventType.PICKED_UP to listOf(
                "order picked up", "picked up order", "pickup completed", "pickup complete",
                "užsakymas paimtas", "uzsakymas paimtas", "заказ получен", "замовлення отримано",
            ),
            DeliveryEventType.ARRIVED_DROPOFF to listOf(
                "arrived at drop-off", "arrived at dropoff", "arrived at customer", "at customer location",
            ),
            DeliveryEventType.ARRIVED_PICKUP to listOf(
                "arrived at pickup", "at pickup location", "i'm at pickup", "i am at pickup",
            ),
            DeliveryEventType.ACCEPTED to listOf(
                "navigate to pickup", "head to pickup", "going to pickup",
            ),
        )
        ordered.forEach { (type, cues) ->
            cues.firstOrNull(lower::contains)?.let { return DeliveryLifecycleEvidence(type, it) }
        }
        return null
    }

    private fun stopKey(text: String, type: DeliveryEventType): String? {
        val addresses = CourierSignals.likelyAddresses(text)
        val chosen = when (type) {
            DeliveryEventType.ARRIVED_PICKUP, DeliveryEventType.PICKED_UP -> addresses.firstOrNull()
            DeliveryEventType.ARRIVED_DROPOFF, DeliveryEventType.DELIVERED -> addresses.lastOrNull()
            else -> null
        } ?: return null
        return CourierSignals.normalizeBuildingAddress(chosen)?.first
    }

    private fun record(context: Context, offerId: Long, event: DeliveryTimelineEvent) {
        runCatching { RouteResearchDatabase.get(context).recordDeliveryEvent(offerId, event) }
            .onFailure {
                CaptureEventLog.append(
                    context,
                    stage = "lifecycle_store_failed",
                    message = it.javaClass.simpleName,
                    dedupeWindowMs = 30_000L,
                )
            }
    }

    private fun keyPrefix(packageName: String): String = packageName.replace('.', '_')
}
