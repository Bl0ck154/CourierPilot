package com.block154.courierpilot

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal data class ScreenPoint(val x: Double, val y: Double)

internal data class KnownMapAnchor(
    val screen: ScreenPoint,
    val geo: RoutePoint,
)

/** Small-area screen-to-geo transform that refuses to guess scale/orientation. */
internal data class LocalMapTransform(
    val anchor: KnownMapAnchor,
    val metersPerPixel: Double,
    val clockwiseRotationDegrees: Double = 0.0,
) {
    init {
        require(metersPerPixel > 0.0 && metersPerPixel.isFinite())
        require(clockwiseRotationDegrees.isFinite())
    }

    fun screenToGeo(target: ScreenPoint): RoutePoint {
        val rawEast = (target.x - anchor.screen.x) * metersPerPixel
        val rawNorth = -(target.y - anchor.screen.y) * metersPerPixel
        val angle = -clockwiseRotationDegrees * PI / 180.0
        val east = rawEast * cos(angle) - rawNorth * sin(angle)
        val north = rawEast * sin(angle) + rawNorth * cos(angle)
        val latitudeRadians = anchor.geo.latitude * PI / 180.0
        val latDelta = north / METERS_PER_DEGREE_LATITUDE
        val lonScale = METERS_PER_DEGREE_LATITUDE * cos(latitudeRadians)
        require(kotlin.math.abs(lonScale) > 1.0) { "Longitude scale is unstable near the poles" }
        val lonDelta = east / lonScale
        return RoutePoint(anchor.geo.latitude + latDelta, anchor.geo.longitude + lonDelta)
    }

    companion object {
        private const val METERS_PER_DEGREE_LATITUDE = 111_320.0

        fun fromTwoAnchors(first: KnownMapAnchor, second: KnownMapAnchor): LocalMapTransform {
            val screenDx = second.screen.x - first.screen.x
            val screenNorth = -(second.screen.y - first.screen.y)
            val pixelDistance = hypot(screenDx, screenNorth)
            require(pixelDistance >= 2.0) { "Screen anchors are too close to establish map scale" }
            val averageLatitudeRadians = ((first.geo.latitude + second.geo.latitude) / 2.0) * PI / 180.0
            val northMeters = (second.geo.latitude - first.geo.latitude) * METERS_PER_DEGREE_LATITUDE
            val eastMeters = (second.geo.longitude - first.geo.longitude) * METERS_PER_DEGREE_LATITUDE * cos(averageLatitudeRadians)
            val geoDistance = hypot(eastMeters, northMeters)
            require(geoDistance >= 1.0) { "Geographic anchors are too close to establish map scale" }
            val rawAngle = atan2(screenNorth, screenDx)
            val worldAngle = atan2(northMeters, eastMeters)
            val clockwiseRotation = normalizeDegrees((rawAngle - worldAngle) * 180.0 / PI)
            return LocalMapTransform(first, geoDistance / pixelDistance, clockwiseRotation)
        }

        private fun normalizeDegrees(value: Double): Double {
            var normalized = value % 360.0
            if (normalized > 180.0) normalized -= 360.0
            if (normalized <= -180.0) normalized += 360.0
            return normalized
        }
    }
}

internal enum class BoltMarkerKind { CURRENT_LOCATION, PICKUP, DROPOFF, UNKNOWN }

internal data class BoltMarkerEvidence(
    val kind: BoltMarkerKind,
    val screenCenter: ScreenPoint,
    val semanticLabel: String? = null,
    val viewId: String? = null,
    val confidence: Double,
) {
    init { require(confidence in 0.0..1.0) }
}

internal data class BoltMapRecoveryEvidence(
    val currentLocation: RoutePoint,
    val currentLocationMarker: BoltMarkerEvidence,
    val targetMarkers: List<BoltMarkerEvidence>,
    val metersPerPixel: Double? = null,
    val clockwiseRotationDegrees: Double? = null,
) {
    val canProjectCoordinates: Boolean
        get() = metersPerPixel != null && clockwiseRotationDegrees != null

    fun buildTransform(): LocalMapTransform {
        require(currentLocationMarker.kind == BoltMarkerKind.CURRENT_LOCATION)
        return LocalMapTransform(
            KnownMapAnchor(currentLocationMarker.screenCenter, currentLocation),
            requireNotNull(metersPerPixel) { "Map scale has not been established" },
            requireNotNull(clockwiseRotationDegrees) { "Map orientation has not been established" },
        )
    }
}
