package com.block154.courierpilot

internal data class GpsTraceSample(
    val timestampMillis: Long,
    val point: RoutePoint,
    val accuracyMeters: Float? = null,
    val speedMetersPerSecond: Float? = null,
)

internal data class MatchedEdgeTraversal(
    val edgeId: String,
    val enteredAtMillis: Long,
    val exitedAtMillis: Long,
    val distanceMeters: Int,
) {
    val durationSeconds: Double
        get() = (exitedAtMillis - enteredAtMillis).coerceAtLeast(0L) / 1000.0
}

internal data class SegmentTravelStats(
    val edgeId: String,
    val sampleCount: Int,
    val medianSeconds: Double,
    val medianMeters: Int,
    val lastObservedAtMillis: Long,
)

/**
 * Intentionally boring statistics first: no ML. A segment only becomes personalized after enough
 * successful traversals, and median duration limits the effect of one abnormal stop/red light.
 */
internal object PersonalRouteStatistics {
    const val MIN_SEGMENT_SAMPLES = 5

    fun summarize(traversals: List<MatchedEdgeTraversal>): List<SegmentTravelStats> = traversals
        .filter { it.edgeId.isNotBlank() && it.durationSeconds > 0.0 && it.distanceMeters > 0 }
        .groupBy { it.edgeId }
        .map { (edgeId, rows) ->
            SegmentTravelStats(
                edgeId = edgeId,
                sampleCount = rows.size,
                medianSeconds = median(rows.map { it.durationSeconds }),
                medianMeters = median(rows.map { it.distanceMeters.toDouble() }).toInt(),
                lastObservedAtMillis = rows.maxOf { it.exitedAtMillis },
            )
        }
        .sortedBy { it.edgeId }

    fun personalizedSeconds(stats: SegmentTravelStats): Double? =
        stats.medianSeconds.takeIf { stats.sampleCount >= MIN_SEGMENT_SAMPLES }

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}

internal data class RestaurantWaitObservation(
    val venueKey: String,
    val arrivedAtMillis: Long,
    val pickedUpAtMillis: Long,
) {
    val waitSeconds: Double
        get() = (pickedUpAtMillis - arrivedAtMillis).coerceAtLeast(0L) / 1000.0
}

internal data class RestaurantWaitStats(
    val venueKey: String,
    val sampleCount: Int,
    val medianWaitSeconds: Double,
)

internal object RestaurantWaitStatistics {
    const val MIN_WAIT_SAMPLES = 3

    fun summarize(observations: List<RestaurantWaitObservation>): List<RestaurantWaitStats> = observations
        .filter { it.venueKey.isNotBlank() && it.waitSeconds >= 0.0 }
        .groupBy { it.venueKey }
        .map { (key, rows) ->
            val sorted = rows.map { it.waitSeconds }.sorted()
            val middle = sorted.size / 2
            val median = if (sorted.size % 2 == 1) sorted[middle]
            else (sorted[middle - 1] + sorted[middle]) / 2.0
            RestaurantWaitStats(key, rows.size, median)
        }
        .sortedBy { it.venueKey }

    fun usableMedianWaitSeconds(stats: RestaurantWaitStats): Double? =
        stats.medianWaitSeconds.takeIf { stats.sampleCount >= MIN_WAIT_SAMPLES }
}
