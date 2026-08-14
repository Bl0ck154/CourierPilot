package com.block154.courierpilot

internal object GpsTraceDetailedExport {
    fun geoJson(sessionId: Long, points: List<GpsTracePoint>): String {
        val coordinates = points.joinToString(",") { point -> "[${point.longitude},${point.latitude}]" }
        val timestamps = points.joinToString(",") { it.recordedAt.toString() }
        val accuracies = points.joinToString(",") { it.accuracyMeters?.toString() ?: "null" }
        val speeds = points.joinToString(",") { it.speedMetersPerSecond?.toString() ?: "null" }
        return """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"source":"courierpilot_gps_trace_v1","session_id":$sessionId,"sample_count":${points.size},"timestamps_ms":[$timestamps],"accuracy_m":[$accuracies],"speed_mps":[$speeds]},"geometry":{"type":"LineString","coordinates":[$coordinates]}}]}"""
    }
}
