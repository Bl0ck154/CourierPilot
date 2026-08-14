package com.block154.courierpilot

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Pure Valhalla JSON contract shared by the research screen and the HTTPS route provider. */
internal object ValhallaContract {
    const val PROVIDER_NAME = "valhalla"

    fun buildRoutePayload(request: RouteRequest): String {
        RouteIntelligencePolicy.validate(request)

        val root = JSONObject()
        val locations = JSONArray()
        request.points.forEach { point ->
            locations.put(
                JSONObject()
                    .put("lat", point.latitude)
                    .put("lon", point.longitude)
            )
        }
        root.put("locations", locations)
        root.put("costing", costingName(request.profile))
        root.put("costing_options", costingOptions(request.profile))
        root.put("directions_options", JSONObject().put("units", "kilometers"))
        return root.toString()
    }

    fun parseRouteResponse(profile: RouteProfile, json: String, httpStatus: Int? = null): RouteResult {
        val root = JSONObject(json)
        val trip = root.getJSONObject("trip")
        val summary = trip.getJSONObject("summary")
        val distanceKm = summary.getDouble("length")
        val durationSeconds = summary.getDouble("time").roundToInt()
        val legsJson = trip.optJSONArray("legs") ?: JSONArray()
        val shapes = buildList {
            for (index in 0 until legsJson.length()) {
                val shape = legsJson.optJSONObject(index)?.optString("shape").orEmpty()
                if (shape.isNotBlank()) add(shape)
            }
        }
        val warnings = parseWarnings(trip.optJSONArray("warnings") ?: root.optJSONArray("warnings"))

        require(distanceKm >= 0.0) { "Valhalla returned a negative distance" }
        require(durationSeconds >= 0) { "Valhalla returned a negative duration" }

        return RouteResult(
            provider = PROVIDER_NAME,
            profile = profile,
            distanceMeters = (distanceKm * 1_000.0).roundToInt(),
            durationSeconds = durationSeconds,
            legShapes = shapes,
            httpStatus = httpStatus,
            warnings = warnings,
        )
    }

    fun parseErrorMessage(json: String): String {
        return runCatching {
            val root = JSONObject(json)
            root.optString("error").takeIf { it.isNotBlank() }
                ?: root.optString("status_message").takeIf { it.isNotBlank() }
                ?: "Valhalla request failed"
        }.getOrDefault("Valhalla request failed")
    }

    private fun parseWarnings(array: JSONArray?): List<String> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            when (val warning = array.opt(index)) {
                is String -> warning.takeIf { it.isNotBlank() }?.let(::add)
                is JSONObject -> {
                    val message = warning.optString("text").takeIf { it.isNotBlank() }
                        ?: warning.optString("message").takeIf { it.isNotBlank() }
                        ?: warning.toString()
                    add(message)
                }
            }
        }
    }

    private fun costingName(profile: RouteProfile): String = when (profile) {
        RouteProfile.PEDESTRIAN_SHORTCUT -> "pedestrian"
        RouteProfile.CYCLEWAY_BIASED -> "bicycle"
    }

    private fun costingOptions(profile: RouteProfile): JSONObject {
        return when (profile) {
            RouteProfile.PEDESTRIAN_SHORTCUT -> JSONObject().put(
                "pedestrian",
                JSONObject()
                    // Research starting value only. The server agent must verify current API bounds.
                    .put("step_penalty", 3600)
            )

            RouteProfile.CYCLEWAY_BIASED -> JSONObject().put(
                "bicycle",
                JSONObject()
                    .put("bicycle_type", "hybrid")
                    .put("use_roads", 0.2)
                    .put("use_hills", 0.5)
                    .put("avoid_bad_surfaces", 0.2)
                    .put("cycling_speed", 25)
            )
        }
    }
}
