package com.block154.courierpilot

import android.content.Context
import java.io.File
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

    fun nearestUnusedIndex(recovered: List<RoutePoint>, truth: RoutePoint, used: Set<Int>): Int? =
        recovered.indices.filterNot { it in used }.minByOrNull { errorMeters(recovered[it], truth) }

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
    private data class Pending(
        val diagnostic: BoltRecoveryDiagnostics,
        val etaMinutes: Int?,
        val realRoutePrefix: List<RoutePoint>,
        val seenAddresses: MutableSet<String> = mutableSetOf(),
        val usedMarkers: MutableSet<Int> = mutableSetOf(),
        var inFlight: Boolean = false,
    )
    private val offers = LinkedHashMap<Long, Pending>()
    private val worker = Executors.newSingleThreadExecutor()

    @Synchronized
    fun remember(context: Context, outcome: AutomaticBoltRouteOutcome, etaMinutes: Int?) {
        val diagnostics = outcome.diagnostics ?: return
        if (diagnostics.projectedDropoffs.isEmpty()) return
        offers[outcome.offerId] = Pending(
            diagnostics, etaMinutes,
            outcome.waypoints.filter { it.kind != WaypointKind.DROPOFF }.map { it.point },
        )
        while (offers.size > 24) offers.remove(offers.keys.first())
        val app = context.applicationContext
        worker.execute {
            pruneArchives(app)
            archiveMatchingSample(app, outcome.offerId)
        }
    }

    /** Archive only the research sample captured alongside this offer; never a later offer's. */
    /** Mirror existing screenshot retention (0 means keep indefinitely). */
    private fun pruneArchives(context: Context) {
        val days = CaptureStorageSettings.retentionDays(context)
        if (days == 0) return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        File(context.filesDir, "diagnostics/bolt-truth").listFiles()
            ?.filter(File::isDirectory)
            ?.filter { it.lastModified() < cutoff }
            ?.forEach(File::deleteRecursively)
    }

    private fun archiveMatchingSample(context: Context, offerId: Long) {
        val recordedAt = runCatching { OfferDatabase.get(context).findById(offerId)?.capturedAt }
            .getOrNull() ?: return
        val sample = BoltAccessibilityDiagnostics.summary(context) ?: return
        if (sample.capturedAt < recordedAt || sample.capturedAt - recordedAt > 15_000L) return
        val target = File(context.filesDir, "diagnostics/bolt-truth/$offerId").apply { mkdirs() }
        BoltAccessibilityDiagnostics.sampleFiles(context).forEach { source ->
            if (source.length() in 1..8_000_000) runCatching {
                source.copyTo(File(target, source.name), overwrite = true)
            }
        }
    }

    fun matchingResearchFiles(context: Context, offerIds: List<Long>): List<File> =
        offerIds.distinct().take(20).flatMap { offerId ->
            val dir = File(context.filesDir, "diagnostics/bolt-truth/$offerId")
            listOf(
                BoltAccessibilityDiagnostics.TREE_FILE,
                BoltAccessibilityDiagnostics.METADATA_FILE,
                BoltAccessibilityDiagnostics.SCREENSHOT_FILE,
            ).map { File(dir, it) }.filter(File::isFile)
        }

    fun observeAcceptedScreen(context: Context, offerId: Long, text: String) {
        val address = DeliveryScreenDetailsExtractor.addressValueForPlatform(
            CourierSignals.BOLT_PACKAGE, text,
        )?.takeIf(String::isNotBlank) ?: return
        val addressKey = address.trim().lowercase()
        val pending = synchronized(this) {
            offers[offerId]?.takeUnless { it.inFlight || addressKey in it.seenAddresses }?.also {
                it.inFlight = true
            }
        } ?: return
        val app = context.applicationContext
        worker.execute {
            val actual = PhotonAddressGeocoder.resolve(address, null)
            if (actual == null) {
                synchronized(this) { pending.inFlight = false }
                return@execute
            }
            val index = synchronized(this) {
                BoltRecoveryTruthMath.nearestUnusedIndex(
                    pending.diagnostic.projectedDropoffs, actual, pending.usedMarkers,
                )
            }
            if (index == null) {
                synchronized(this) { pending.inFlight = false }
                return@execute
            }
            val recovered = pending.diagnostic.projectedDropoffs[index]
            val d = pending.diagnostic
            // Only an actual customer address may train ETA. A route to a projected pin cannot.
            // Multi-drop permutations are still ambiguous, so do not train those until mapped.
            val trueRouteMeters = if (d.projectedDropoffs.size == 1 &&
                pending.realRoutePrefix.size >= 2 && pending.etaMinutes != null
            ) {
                runCatching {
                    val endpoint = RouteEndpointSettings.load(app).validated()
                    val comparison = RouteComparisonEngine(ValhallaRouteProvider(endpoint)).compare(
                        pending.realRoutePrefix + actual,
                    )
                    comparison.cycleway.getOrNull()?.distanceMeters
                }.getOrNull()?.takeIf { it > 0 }
            } else null
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
                routeMeters = trueRouteMeters,
            )
            runCatching {
                val database = RouteResearchDatabase.get(app)
                database.recordBoltRecoveryTruth(row)
                database.pruneBoltRecoveryTruth(CaptureStorageSettings.retentionDays(app))
                if (trueRouteMeters != null && pending.etaMinutes != null) {
                    BoltEtaDistanceModel(app).observe(pending.etaMinutes, trueRouteMeters)
                }
            }
            synchronized(this) {
                pending.seenAddresses.add(addressKey)
                pending.usedMarkers.add(index)
                pending.inFlight = false
                if (pending.usedMarkers.size >= pending.diagnostic.projectedDropoffs.size) {
                    offers.remove(offerId)
                }
            }
        }
    }
}
