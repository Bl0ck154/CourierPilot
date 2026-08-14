package com.block154.courierpilot

import org.json.JSONArray
import org.json.JSONObject

internal object RoutePolyline {
    fun decodePolyline6(encoded: String): List<RoutePoint> {
        if (encoded.isBlank()) return emptyList()
        var index = 0
        var lat = 0
        var lon = 0
        val points = ArrayList<RoutePoint>()
        while (index < encoded.length) {
            val latResult = decodeValue(encoded, index)
            index = latResult.nextIndex
            lat += latResult.delta
            if (index >= encoded.length) break
            val lonResult = decodeValue(encoded, index)
            index = lonResult.nextIndex
            lon += lonResult.delta
            points += RoutePoint(lat / 1_000_000.0, lon / 1_000_000.0)
        }
        return points
    }

    fun decodeRoute(route: RouteResult): List<RoutePoint> = route.legShapes.flatMap(::decodePolyline6)

    fun comparisonGeoJson(comparison: RouteComparison): String {
        val features = JSONArray()
        addFeature(features, "pedestrian", comparison.pedestrian.getOrNull())
        addFeature(features, "cycleway", comparison.cycleway.getOrNull())
        return JSONObject().put("type", "FeatureCollection").put("features", features).toString(2)
    }

    private fun addFeature(features: JSONArray, name: String, route: RouteResult?) {
        if (route == null) return
        val coordinates = JSONArray()
        decodeRoute(route).forEach { point ->
            coordinates.put(JSONArray().put(point.longitude).put(point.latitude))
        }
        features.put(JSONObject()
            .put("type", "Feature")
            .put("properties", JSONObject()
                .put("profile", name)
                .put("distance_m", route.distanceMeters)
                .put("duration_s", route.durationSeconds))
            .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coordinates)))
    }

    private data class DecodedValue(val delta: Int, val nextIndex: Int)

    private fun decodeValue(encoded: String, startIndex: Int): DecodedValue {
        var index = startIndex
        var result = 0
        var shift = 0
        while (index < encoded.length) {
            val byte = encoded[index++].code - 63
            result = result or ((byte and 0x1f) shl shift)
            shift += 5
            if (byte < 0x20) break
            require(shift <= 30) { "Invalid encoded polyline" }
        }
        val delta = if ((result and 1) != 0) (result shr 1).inv() else result shr 1
        return DecodedValue(delta, index)
    }
}
