package com.block154.courierpilot

import android.content.ContentValues

internal data class GpsTraceSessionSummary(
    val sessionId: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val sampleCount: Int,
    val distanceMeters: Double,
    val averageSpeedMetersPerSecond: Double?,
)

internal fun RouteResearchDatabase.closeOpenGpsSessions(endedAt: Long) {
    writableDatabase.update(
        "gps_sessions",
        ContentValues().apply { put("ended_at", endedAt) },
        "ended_at IS NULL",
        null,
    )
}

internal fun RouteResearchDatabase.startGpsSession(
    startedAt: Long,
    sourcePlatform: String? = null,
    purpose: String = "manual_route_learning",
): Long = writableDatabase.insertOrThrow(
    "gps_sessions",
    null,
    ContentValues().apply {
        put("started_at", startedAt)
        put("source_platform", sourcePlatform)
        put("purpose", purpose)
    },
)

internal fun RouteResearchDatabase.endGpsSession(sessionId: Long, endedAt: Long) {
    writableDatabase.update(
        "gps_sessions",
        ContentValues().apply { put("ended_at", endedAt) },
        "id = ? AND ended_at IS NULL",
        arrayOf(sessionId.toString()),
    )
}

internal fun RouteResearchDatabase.insertGpsSample(sessionId: Long, point: GpsTracePoint): Long =
    writableDatabase.insertOrThrow(
        "gps_samples",
        null,
        ContentValues().apply {
            put("session_id", sessionId)
            put("recorded_at", point.recordedAt)
            put("latitude", point.latitude)
            put("longitude", point.longitude)
            point.accuracyMeters?.let { put("accuracy_m", it) }
            point.speedMetersPerSecond?.let { put("speed_mps", it) }
        },
    )

internal fun RouteResearchDatabase.latestGpsSessionId(): Long? = readableDatabase.rawQuery(
    "SELECT id FROM gps_sessions ORDER BY started_at DESC LIMIT 1",
    null,
).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

internal fun RouteResearchDatabase.gpsSamples(sessionId: Long, limit: Int = 50_000): List<GpsTracePoint> =
    readableDatabase.query(
        "gps_samples",
        arrayOf("recorded_at", "latitude", "longitude", "accuracy_m", "speed_mps"),
        "session_id = ?",
        arrayOf(sessionId.toString()),
        null,
        null,
        "recorded_at ASC",
        limit.coerceIn(1, 100_000).toString(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    GpsTracePoint(
                        recordedAt = cursor.getLong(0),
                        latitude = cursor.getDouble(1),
                        longitude = cursor.getDouble(2),
                        accuracyMeters = if (cursor.isNull(3)) null else cursor.getFloat(3),
                        speedMetersPerSecond = if (cursor.isNull(4)) null else cursor.getFloat(4),
                    )
                )
            }
        }
    }

internal fun RouteResearchDatabase.gpsSessionSummary(sessionId: Long): GpsTraceSessionSummary? {
    val header = readableDatabase.query(
        "gps_sessions",
        arrayOf("started_at", "ended_at"),
        "id = ?",
        arrayOf(sessionId.toString()),
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else cursor.getLong(0) to if (cursor.isNull(1)) null else cursor.getLong(1)
    } ?: return null

    val points = gpsSamples(sessionId)
    var distance = 0.0
    points.zipWithNext().forEach { (a, b) -> distance += GpsTracePolicy.distanceMeters(a, b) }
    val durationSeconds = if (points.size >= 2) {
        ((points.last().recordedAt - points.first().recordedAt).coerceAtLeast(0L) / 1000.0)
    } else {
        0.0
    }
    val averageSpeed = if (durationSeconds > 0.0) distance / durationSeconds else null
    return GpsTraceSessionSummary(
        sessionId = sessionId,
        startedAt = header.first,
        endedAt = header.second,
        sampleCount = points.size,
        distanceMeters = distance,
        averageSpeedMetersPerSecond = averageSpeed,
    )
}

internal fun RouteResearchDatabase.latestGpsSessionSummary(): GpsTraceSessionSummary? =
    latestGpsSessionId()?.let(::gpsSessionSummary)
