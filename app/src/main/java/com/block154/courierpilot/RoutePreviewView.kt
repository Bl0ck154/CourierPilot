package com.block154.courierpilot

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Interactive route-research map backed by OpenStreetMap tiles.
 *
 * The route geometry itself still comes only from our Valhalla response. If tiles are unavailable,
 * osmdroid keeps rendering the route overlays so research is still usable without a basemap.
 */
internal class RoutePreviewView(context: Context) : FrameLayout(context) {
    private val mapView: MapView

    init {
        Configuration.getInstance().userAgentValue = context.packageName
        setBackgroundColor(Color.WHITE)

        mapView = MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            setTilesScaledToDpi(true)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            controller.setZoom(14.0)
        }
        addView(
            mapView,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(260f).toInt()),
        )
        resetOverlays()
    }

    fun setRoutes(pedestrian: RouteResult?, cycleway: RouteResult?) {
        val pedestrianPoints = pedestrian?.let(RoutePolyline::decodeRoute).orEmpty()
        val cyclewayPoints = cycleway?.let(RoutePolyline::decodeRoute).orEmpty()

        resetOverlays()
        addRoute(pedestrianPoints, PEDESTRIAN_COLOR, "Pedestrian shortcut")
        addRoute(cyclewayPoints, CYCLEWAY_COLOR, "Cycleway biased")

        val all = (pedestrianPoints + cyclewayPoints)
            .map { GeoPoint(it.latitude, it.longitude) }
        if (all.isNotEmpty()) {
            val reference = pedestrianPoints.ifEmpty { cyclewayPoints }
            reference.firstOrNull()?.let { addEndpoint(it, "Start", Color.parseColor("#111827")) }
            reference.lastOrNull()?.let { addEndpoint(it, "Finish", Color.parseColor("#111827")) }
            fitRoutes(all)
        }
        mapView.invalidate()
    }

    private fun resetOverlays() {
        mapView.overlays.clear()
        mapView.overlays += CopyrightOverlay(context)
    }

    private fun addRoute(points: List<RoutePoint>, color: Int, title: String) {
        if (points.size < 2) return
        mapView.overlays += Polyline(mapView).apply {
            setPoints(points.map { GeoPoint(it.latitude, it.longitude) })
            outlinePaint.color = color
            outlinePaint.strokeWidth = dp(5f)
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            this.title = title
        }
    }

    private fun addEndpoint(point: RoutePoint, title: String, color: Int) {
        val markerSize = dp(16f).toInt()
        val markerDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(2f).toInt(), Color.WHITE)
            setSize(markerSize, markerSize)
        }
        mapView.overlays += Marker(mapView).apply {
            position = GeoPoint(point.latitude, point.longitude)
            icon = markerDrawable
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            this.title = title
        }
    }

    private fun fitRoutes(points: List<GeoPoint>) {
        post {
            if (points.size == 1) {
                mapView.controller.setCenter(points.first())
                mapView.controller.setZoom(17.0)
                return@post
            }
            val bounds = BoundingBox.fromGeoPoints(points)
            mapView.zoomToBoundingBox(bounds, true, dp(36f).toInt())
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private val PEDESTRIAN_COLOR = Color.parseColor("#D97706")
        private val CYCLEWAY_COLOR = Color.parseColor("#2563EB")
    }
}
