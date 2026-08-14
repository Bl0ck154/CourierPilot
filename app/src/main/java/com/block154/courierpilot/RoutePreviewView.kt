package com.block154.courierpilot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight geometry-only preview for research. It intentionally has no third-party map tiles;
 * the goal is to expose detours and route-shape differences without sending coordinates elsewhere.
 */
internal class RoutePreviewView(context: Context) : View(context) {
    private var pedestrian: List<RoutePoint> = emptyList()
    private var cycleway: List<RoutePoint> = emptyList()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E5E7EB")
        strokeWidth = dp(1f)
    }
    private val pedestrianPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D97706")
        strokeWidth = dp(4f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val cyclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2563EB")
        strokeWidth = dp(4f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val endpointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111827")
        style = Paint.Style.FILL
    }

    fun setRoutes(pedestrian: RouteResult?, cycleway: RouteResult?) {
        this.pedestrian = pedestrian?.let(RoutePolyline::decodeRoute).orEmpty()
        this.cycleway = cycleway?.let(RoutePolyline::decodeRoute).orEmpty()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, dp(220f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        val step = dp(32f)
        var x = 0f
        while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint); x += step }
        var y = 0f
        while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, gridPaint); y += step }

        val all = pedestrian + cycleway
        if (all.size < 2) return

        val centerLat = all.map { it.latitude }.average()
        val lonFactor = cos(centerLat * PI / 180.0).coerceAtLeast(0.01)
        val projected = all.map { point ->
            ProjectedPoint(point.longitude * lonFactor, point.latitude)
        }
        val minX = projected.minOf { it.x }
        val maxX = projected.maxOf { it.x }
        val minY = projected.minOf { it.y }
        val maxY = projected.maxOf { it.y }
        val spanX = max(maxX - minX, 0.000001)
        val spanY = max(maxY - minY, 0.000001)
        val padding = dp(18f)
        val availableWidth = (width - 2 * padding).coerceAtLeast(1f)
        val availableHeight = (height - 2 * padding).coerceAtLeast(1f)
        val scale = min(availableWidth / spanX.toFloat(), availableHeight / spanY.toFloat())
        val usedWidth = spanX.toFloat() * scale
        val usedHeight = spanY.toFloat() * scale
        val offsetX = padding + (availableWidth - usedWidth) / 2f
        val offsetY = padding + (availableHeight - usedHeight) / 2f

        fun map(point: RoutePoint): Pair<Float, Float> {
            val projectedX = point.longitude * lonFactor
            val px = offsetX + ((projectedX - minX).toFloat() * scale)
            val py = offsetY + ((maxY - point.latitude).toFloat() * scale)
            return px to py
        }

        fun drawRoute(points: List<RoutePoint>, paint: Paint) {
            if (points.size < 2) return
            val path = Path()
            points.forEachIndexed { index, point ->
                val (px, py) = map(point)
                if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            canvas.drawPath(path, paint)
        }

        drawRoute(pedestrian, pedestrianPaint)
        drawRoute(cycleway, cyclePaint)
        val reference = pedestrian.ifEmpty { cycleway }
        if (reference.isNotEmpty()) {
            map(reference.first()).also { canvas.drawCircle(it.first, it.second, dp(6f), endpointPaint) }
            map(reference.last()).also { canvas.drawCircle(it.first, it.second, dp(6f), endpointPaint) }
        }
    }

    private data class ProjectedPoint(val x: Double, val y: Double)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
