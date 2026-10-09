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

/**
 * Last line of defence against a wrong map projection: a full Bolt route must be roughly compatible
 * with Bolt's own total ETA. Bounds are wide enough for walking (~80 m/min) and car couriers, but a
 * 0.6 km route for an 18 min offer (real 0.16.0 case) is rejected instead of shown as 🔥.
 */
internal object BoltRouteEtaPlausibility {
    const val MIN_RATIO = 0.25
    const val MAX_RATIO = 3.0

    fun isPlausible(routeMeters: Int?, etaMinutes: Int?, model: BoltEtaDistanceModel): Boolean {
        val minutes = etaMinutes?.takeIf { it in 1..240 } ?: return true
        val meters = routeMeters?.takeIf { it > 0 } ?: return true
        val expected = model.estimateMeters(minutes).takeIf { it > 0 } ?: return true
        return meters.toDouble() / expected in MIN_RATIO..MAX_RATIO
    }

    /** Bolt's accept button shows the whole-offer minutes; fall back to the two per-leg labels. */
    fun totalMinutes(etas: BoltEtas): Int? = etas.totalMin
        ?: if (etas.toPickupMin != null && etas.toCustomerMin != null) etas.toPickupMin + etas.toCustomerMin else null
}
