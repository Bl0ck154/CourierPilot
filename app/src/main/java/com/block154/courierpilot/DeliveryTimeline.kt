package com.block154.courierpilot

internal enum class DeliveryEventType {
    OFFER_CAPTURED,
    ACCEPTED,
    ARRIVED_PICKUP,
    PICKED_UP,
    ARRIVED_DROPOFF,
    DELIVERED,
    CANCELLED,
}

internal data class DeliveryTimelineEvent(
    val type: DeliveryEventType,
    val timestampMillis: Long,
    val stopKey: String? = null,
    val source: String,
    val confidence: Double = 1.0,
) {
    init {
        require(timestampMillis >= 0)
        require(source.isNotBlank())
        require(confidence in 0.0..1.0)
    }
}

internal data class PickupWaitMetric(
    val stopKey: String,
    val arrivedAtMillis: Long,
    val pickedUpAtMillis: Long,
) {
    val waitSeconds: Int
        get() = ((pickedUpAtMillis - arrivedAtMillis).coerceAtLeast(0L) / 1000L).toInt()
}

internal data class DeliveryTimelineMetrics(
    val acceptedAtMillis: Long?,
    val completedAtMillis: Long?,
    val acceptedToCompleteSeconds: Int?,
    val pickupWaits: List<PickupWaitMetric>,
    val cancelled: Boolean,
)

/**
 * Derives facts only from observed lifecycle events. Future Accessibility classifiers can add events
 * with source/confidence; this layer never infers ACCEPTED/DELIVERED merely because time passed.
 */
internal object DeliveryTimelineAnalyzer {
    fun analyze(events: List<DeliveryTimelineEvent>): DeliveryTimelineMetrics {
        val ordered = events.sortedBy { it.timestampMillis }
        val accepted = ordered.firstOrNull { it.type == DeliveryEventType.ACCEPTED }?.timestampMillis
        val completed = ordered.lastOrNull { it.type == DeliveryEventType.DELIVERED }?.timestampMillis
        val cancelled = ordered.any { it.type == DeliveryEventType.CANCELLED }

        val pickupWaits = ordered
            .filter { it.type == DeliveryEventType.ARRIVED_PICKUP && !it.stopKey.isNullOrBlank() }
            .mapNotNull { arrival ->
                val pickup = ordered.firstOrNull {
                    it.type == DeliveryEventType.PICKED_UP &&
                        it.stopKey == arrival.stopKey &&
                        it.timestampMillis >= arrival.timestampMillis
                } ?: return@mapNotNull null
                PickupWaitMetric(
                    stopKey = arrival.stopKey!!,
                    arrivedAtMillis = arrival.timestampMillis,
                    pickedUpAtMillis = pickup.timestampMillis,
                )
            }
            .distinctBy { "${it.stopKey}|${it.arrivedAtMillis}|${it.pickedUpAtMillis}" }

        val total = if (accepted != null && completed != null && completed >= accepted) {
            ((completed - accepted) / 1000L).toInt()
        } else {
            null
        }

        return DeliveryTimelineMetrics(
            acceptedAtMillis = accepted,
            completedAtMillis = completed,
            acceptedToCompleteSeconds = total,
            pickupWaits = pickupWaits,
            cancelled = cancelled,
        )
    }
}
