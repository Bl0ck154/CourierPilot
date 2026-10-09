package com.block154.courierpilot

import android.content.Context
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal data class BoltRecoveryTruthRow(
    val offerId: Long,
    val recovered: RoutePoint,
    val truth: RoutePoint,
    val errorMeters: Double,
    val scaleMetersPerPixel: Double?,
    val anchorBaselinePx: Double?,
    val anchorBaselineMeters: Double?,
    val pickupMarkerCount: Int,
    val dropoffMarkerCount: Int,
    val etaMinutes: Int?,
    val routeMeters: Int?,
    val createdAt: Long = System.currentTimeMillis(),
)

internal data class BoltRecoveryTruthStats(val count: Int, val medianMeters: Double?, val p80Meters: Double?)

internal object BoltRecoveryTruthMath {
    fun errorMeters(a: RoutePoint, b: RoutePoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dy = lat2 - lat1
        val dx = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dy / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dx / 2).pow(2)
        return 2 * 6371000.0 * asin(min(1.0, sqrt(h)))
    }

    fun nearest(recovered: List<RoutePoint>, truth: RoutePoint): RoutePoint? =
        recovered.minByOrNull { errorMeters(it, truth) }

    fun stats(errors: List<Double>): BoltRecoveryTruthStats {
        val values = errors.filter { it.isFinite() && it >= 0.0 }.sorted()
        if (values.isEmpty()) return BoltRecoveryTruthStats(0, null, null)
        val median = if (values.size % 2 == 1) values[values.size / 2]
            else (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0
        return BoltRecoveryTruthStats(values.size, median, values[(ceil(values.size * 0.8).toInt() - 1).coerceIn(values.indices)])
    }
}

/** Only local memory + local SQLite. Never record customer names/addresses/coordinates in telemetry. */
internal object BoltRecoveryTruth {
    private data class Pending(val diagnostic: BoltRecoveryDiagnostics, val etaMinutes: Int?)
    private val offers = LinkedHashMap<Long, Pending>()
    private val processing = mutableSetOf<Long>()
    private val worker = Executors.newSingleThreadExecutor()

    @Synchronized
    fun remember(outcome: AutomaticBoltRouteOutcome, etaMinutes: Int?) {
        val diagnostics = outcome.diagnostics ?: return
        if (diagnostics.projectedDropoffs.isEmpty()) return
        offers[outcome.offerId] = Pending(diagnostics, etaMinutes)
        while (offers.size > 24) offers.remove(offers.keys.first())
    }

    fun observeAcceptedScreen(context: Context, offerId: Long, text: String) {
        val address = DeliveryScreenDetailsExtractor.addressValueForPlatform(
            CourierSignals.BOLT_PACKAGE, text,
        )?.takeIf(String::isNotBlank) ?: return
        val pending = synchronized(this) {
            if (offerId in processing) null else offers[offerId]?.also { processing.add(offerId) }
        } ?: return
        val app = context.applicationContext
        worker.execute {
            val actual = PhotonAddressGeocoder.resolve(address, null)
            if (actual == null) {
                synchronized(this) { processing.remove(offerId) }
                return@execute
            }
            val recovered = BoltRecoveryTruthMath.nearest(pending.diagnostic.projectedDropoffs, actual)
                ?: return@execute
            val d = pending.diagnostic
            val row = BoltRecoveryTruthRow(
                offerId = offerId,
                recovered = recovered,
                truth = actual,
                errorMeters = BoltRecoveryTruthMath.errorMeters(recovered, actual),
                scaleMetersPerPixel = d.scaleMetersPerPixel,
                anchorBaselinePx = d.anchorBaselinePx,
                anchorBaselineMeters = d.anchorBaselineMeters,
                pickupMarkerCount = d.pickupMarkerCount,
                dropoffMarkerCount = d.dropoffMarkerCount,
                etaMinutes = pending.etaMinutes,
                routeMeters = null, // Never train the ETA prior from an unverified route to a guessed pin.
            )
            runCatching { RouteResearchDatabase.get(app).recordBoltRecoveryTruth(row) }
            synchronized(this) { offers.remove(offerId); processing.remove(offerId) }
        }
    }
}
