package com.block154.courierpilot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max

internal class PilotHeatmapView(context: Context) : View(context) {
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val monthFormat = SimpleDateFormat("MMM", Locale.getDefault())
    private var days: Map<String, DaySummary> = emptyMap()
    private val hitRects = mutableListOf<Pair<RectF, String>>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7280")
        textSize = sp(10f)
    }
    var onDaySelected: ((DaySummary?) -> Unit)? = null

    fun setDays(values: List<DaySummary>) {
        days = values.associateBy { it.day }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(90))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hitRects.clear()
        val cell = dp(5).toFloat()
        val gap = dp(2).toFloat()
        val step = cell + gap
        val top = dp(25).toFloat()
        val left = dp(2).toFloat()
        val weeks = max(1, ((width - left * 2) / step).toInt())
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endOfWeek = today.clone() as Calendar
        endOfWeek.add(Calendar.DAY_OF_YEAR, Calendar.SATURDAY - endOfWeek.get(Calendar.DAY_OF_WEEK))
        val cursor = endOfWeek.clone() as Calendar
        cursor.add(Calendar.DAY_OF_YEAR, -(weeks * 7 - 1))
        val maxCount = max(1, days.values.maxOfOrNull { it.count } ?: 1)
        var lastMonth = -1

        for (week in 0 until weeks) {
            for (row in 0 until 7) {
                val key = dayFormat.format(cursor.time)
                val future = cursor.after(today)
                val count = if (future) 0 else days[key]?.count ?: 0
                paint.color = when {
                    future -> Color.parseColor("#F3F4F6")
                    count <= 0 -> Color.parseColor("#E5E7EB")
                    count <= max(1, maxCount / 4) -> Color.parseColor("#BBF7D0")
                    count <= max(2, maxCount / 2) -> Color.parseColor("#86EFAC")
                    count < maxCount -> Color.parseColor("#4ADE80")
                    else -> Color.parseColor("#16A34A")
                }
                val x = left + week * step
                val y = top + row * step
                val rect = RectF(x, y, x + cell, y + cell)
                canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), paint)
                if (!future) hitRects += RectF(rect) to key
                val month = cursor.get(Calendar.MONTH)
                if (row == 0 && month != lastMonth && cursor.get(Calendar.DAY_OF_MONTH) <= 7) {
                    canvas.drawText(monthFormat.format(cursor.time), x, dp(12).toFloat(), textPaint)
                    lastMonth = month
                }
                cursor.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val hit = hitRects.firstOrNull { it.first.contains(event.x, event.y) } ?: return true
        onDaySelected?.invoke(days[hit.second] ?: DaySummary(hit.second, 0, 0, 0, null, null))
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}

internal class PilotHourlyView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7280")
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }
    private var counts = IntArray(24)

    fun setOffers(records: List<OfferRecord>) {
        counts = IntArray(24)
        val calendar = Calendar.getInstance()
        records.forEach {
            calendar.timeInMillis = it.capturedAt
            counts[calendar.get(Calendar.HOUR_OF_DAY)]++
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(132))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = dp(4).toFloat()
        val right = width - dp(4).toFloat()
        val top = dp(10).toFloat()
        val bottom = height - dp(28).toFloat()
        val maxCount = max(1, counts.maxOrNull() ?: 1)
        val slot = (right - left) / 24f
        val barWidth = max(dp(3).toFloat(), slot * 0.58f)
        counts.forEachIndexed { hour, count ->
            val heightValue = (bottom - top) * count.toFloat() / maxCount
            val cx = left + slot * hour + slot / 2f
            paint.color = if (count == 0) Color.parseColor("#E5E7EB") else Color.parseColor("#2563EB")
            canvas.drawRoundRect(
                RectF(cx - barWidth / 2f, bottom - max(dp(3).toFloat(), heightValue), cx + barWidth / 2f, bottom),
                dp(2f), dp(2f), paint,
            )
        }
        listOf(0, 6, 12, 18, 23).forEach { hour ->
            val cx = left + slot * hour + slot / 2f
            canvas.drawText(hour.toString().padStart(2, '0'), cx, height - dp(8).toFloat(), textPaint)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
