package com.block154.courierpilot

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.roundToInt

/**
 * ETA is a distance prior, not a routing verdict. Only accepted ground-truth routes should
 * update the cycling-speed estimate; absurd app ETAs must not poison subsequent offers.
 */
internal class BoltEtaDistanceModel(private val preferences: SharedPreferences? = null) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("bolt_eta_distance", Context.MODE_PRIVATE)
    )

    private var metresPerMinute = preferences?.getFloat(KEY_SPEED, COLD_START_METERS_PER_MINUTE.toFloat())
        ?.toDouble()?.takeIf { it.isFinite() && it in MIN_SPEED..MAX_SPEED }
        ?: COLD_START_METERS_PER_MINUTE

    @Synchronized
    fun estimateMeters(minutes: Int): Int =
        if (minutes in 1..240) (minutes * metresPerMinute).roundToInt() else 0

    @Synchronized
    fun observe(minutes: Int, routeMeters: Int) {
        if (minutes !in 1..240 || routeMeters <= 0) return
        val observedSpeed = routeMeters.toDouble() / minutes
        if (!observedSpeed.isFinite() || observedSpeed !in MIN_SPEED..MAX_SPEED) return
        metresPerMinute = (1.0 - EWMA_ALPHA) * metresPerMinute + EWMA_ALPHA * observedSpeed
        preferences?.edit()?.putFloat(KEY_SPEED, metresPerMinute.toFloat())?.apply()
    }

    companion object {
        const val COLD_START_METERS_PER_MINUTE = 230.0
        private const val EWMA_ALPHA = 0.15
        private const val MIN_SPEED = 80.0
        private const val MAX_SPEED = 450.0
        private const val KEY_SPEED = "cycling_meters_per_minute"
    }
}
