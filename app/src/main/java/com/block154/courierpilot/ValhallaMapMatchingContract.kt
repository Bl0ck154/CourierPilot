package com.block154.courierpilot

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

internal data class MatchedTraceEdge(
    val edgeId: String,
    val osmWayId: String?,
    val lengthMeters: Int,
    val use: String?,
    val surface: String?,
    val cycleLane: String?,
)

internal data class MatchedTracePoint(
    val point: RoutePoint,
    val type: String,
    val edgeIndex: Int?,
    val distanceFromTracePointMeters: Double?,
)

internal data class MapMatchedTrace(
    val edges: List<MatchedTraceEdge>,
    val matchedPoints: List<MatchedTracePoint>,
    val encodedShape: String?,
)

internal interface TraceMatcher {
    fun match(samples: List<GpsTraceSample>, profile: RouteProfile): Result<MapMatchedTrace>
}

internal object ValhallaMapMatchingContract {
    fun buildTraceAttributesPayload(samples: List<GpsTraceSample>, profile: RouteProfile): String {
        require(samples.size >= 2) { "Map matching requires at least two GPS samples" }
        require(samples.zipWithNext().all { (a, b) -> b.timestampMillis >= a.timestampMillis }) {
            "GPS samples must be ordered by timestamp"
        }
        val firstTimestamp = samples.first().timestampMillis
        val shape = JSONArray()
        samples.forEach { sample ->
            shape.put(JSONObject()
                .put("lat", sample.point.latitude)
                .put("lon", sample.point.longitude)
                .put("time", (sample.timestampMillis - firstTimestamp) / 1000.0))
        }
        val attributes = JSONArray().apply {
            put("edge.id"); put("edge.way_id"); put("edge.length"); put("edge.use")
            put("edge.surface"); put("edge.cycle_lane"); put("matched.point")
            put("matched.type"); put("matched.edge_index"); put("matched.distance_from_trace_point")
            put("shape")
        }
        return JSONObject()
            .put("shape", shape)
            .put("costing", if (profile == RouteProfile.PEDESTRIAN_SHORTCUT) "pedestrian" else "bicycle")
            .put("shape_match", "map_snap")
            .put("trace_options", JSONObject()
                .put("gps_accuracy", representativeAccuracy(samples))
                .put("search_radius", 40))
            .put("directions_options", JSONObject().put("units", "kilometers"))
            .put("filters", JSONObject().put("action", "include").put("attributes", attributes))
            .toString()
    }

    fun parseTraceAttributesResponse(json: String): MapMatchedTrace {
        val root = JSONObject(json)
        val edgesJson = root.optJSONArray("edges") ?: JSONArray()
        val pointsJson = root.optJSONArray("matched_points") ?: JSONArray()
        val edges = buildList {
            for (index in 0 until edgesJson.length()) {
                val edge = edgesJson.optJSONObject(index) ?: continue
                val id = edge.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: continue
                add(MatchedTraceEdge(
                    edgeId = id,
                    osmWayId = edge.opt("way_id")?.toString()?.takeIf { it.isNotBlank() },
                    lengthMeters = (edge.optDouble("length", 0.0) * 1000.0).roundToInt(),
                    use = edge.optString("use").takeIf(String::isNotBlank),
                    surface = edge.optString("surface").takeIf(String::isNotBlank),
                    cycleLane = edge.optString("cycle_lane").takeIf(String::isNotBlank),
                ))
            }
        }
        val matchedPoints = buildList {
            for (index in 0 until pointsJson.length()) {
                val point = pointsJson.optJSONObject(index) ?: continue
                if (!point.has("lat") || !point.has("lon")) continue
                add(MatchedTracePoint(
                    point = RoutePoint(point.getDouble("lat"), point.getDouble("lon")),
                    type = point.optString("type", "unknown"),
                    edgeIndex = point.optInt("edge_index", -1).takeIf { it >= 0 },
                    distanceFromTracePointMeters = point.optDouble("distance_from_trace_point", Double.NaN).takeIf { it.isFinite() },
                ))
            }
        }
        return MapMatchedTrace(edges, matchedPoints, root.optString("shape").takeIf(String::isNotBlank))
    }

    private fun representativeAccuracy(samples: List<GpsTraceSample>): Double {
        val accuracies = samples.mapNotNull { it.accuracyMeters?.toDouble() }
            .filter { it.isFinite() && it > 0.0 }.sorted()
        return if (accuracies.isEmpty()) 8.0 else accuracies[accuracies.size / 2].coerceIn(2.0, 50.0)
    }
}
