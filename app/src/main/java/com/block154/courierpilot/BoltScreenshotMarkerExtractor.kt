package com.block154.courierpilot

import android.graphics.Bitmap
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Lightweight Bolt/Mapbox marker recovery from a clean offer screenshot.
 *
 * Recent Bolt builds expose essentially an empty Accessibility subtree for the map, so marker
 * coordinates have to come from pixels. Bolt stacked offers can contain several blue pickup pins
 * and several green customer pins; this detector therefore keeps every strong marker-sized density
 * peak instead of collapsing each colour to one global winner. It does not OCR or infer names.
 */
internal object BoltScreenshotMarkerExtractor {
    private const val MAP_BOTTOM_FRACTION = 0.72
    private const val SAMPLE_STEP = 2
    private const val MAX_MARKERS_PER_KIND = 4
    private const val RELATIVE_PEAK_THRESHOLD = 0.50

    fun extract(bitmap: Bitmap): BoltSemanticMarkers? {
        if (bitmap.width < 200 || bitmap.height < 300) return null
        val mapBottom = (bitmap.height * MAP_BOTTOM_FRACTION).roundToInt()
            .coerceIn(1, bitmap.height)

        val currentCluster = findClusters(bitmap, mapBottom, Palette.CYAN, 1).firstOrNull() ?: return null
        val pickupClusters = findClusters(bitmap, mapBottom, Palette.BLUE, MAX_MARKERS_PER_KIND)
        val dropoffClusters = findClusters(bitmap, mapBottom, Palette.GREEN, MAX_MARKERS_PER_KIND)
        if (pickupClusters.isEmpty() || dropoffClusters.isEmpty()) return null

        val currentPoint = ScreenPoint(currentCluster.centerX, currentCluster.centerY)
        val pickups = markerEvidence(
            bitmap = bitmap,
            mapBottom = mapBottom,
            palette = Palette.BLUE,
            kind = BoltMarkerKind.PICKUP,
            clusters = pickupClusters,
            duplicateTipDistancePx = 40.0,
            label = "pickup",
        )
        val dropoffs = markerEvidence(
            bitmap = bitmap,
            mapBottom = mapBottom,
            palette = Palette.GREEN,
            kind = BoltMarkerKind.DROPOFF,
            clusters = dropoffClusters,
            // Customer pins can genuinely overlap on dense double orders, so dedupe only tips that
            // are nearly identical. Duplicate density peaks from one icon converge to the same tip.
            duplicateTipDistancePx = 24.0,
            label = "customer",
        )
        if (pickups.isEmpty() || dropoffs.isEmpty()) return null

        return BoltSemanticMarkers(
            currentLocation = BoltMarkerEvidence(
                kind = BoltMarkerKind.CURRENT_LOCATION,
                screenCenter = currentPoint,
                semanticLabel = "Bolt screenshot current-location marker",
                confidence = 0.74,
            ),
            pickups = pickups,
            dropoffs = dropoffs,
            unknown = emptyList(),
        )
    }

    fun looksLikeOfferMap(bitmap: Bitmap): Boolean = extract(bitmap) != null

    private enum class Palette { GREEN, BLUE, CYAN }

    private data class Cluster(
        val centerX: Double,
        val centerY: Double,
        val binSize: Int,
        val score: Int,
    )

    private data class Peak(val column: Int, val row: Int, val score: Int)

    private fun findClusters(
        bitmap: Bitmap,
        mapBottom: Int,
        palette: Palette,
        maxMarkers: Int,
    ): List<Cluster> {
        val width = bitmap.width
        val binSize = maxOf(12, width / 45)
        val columns = (width + binSize - 1) / binSize
        val rows = (mapBottom + binSize - 1) / binSize
        val bins = IntArray(columns * rows)

        val pixels = IntArray(width)
        var y = 0
        while (y < mapBottom) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            var x = 0
            while (x < width) {
                if (matches(pixels[x], palette)) {
                    bins[(y / binSize) * columns + (x / binSize)]++
                }
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }

        val peaks = mutableListOf<Peak>()
        var bestScore = 0
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                var score = 0
                for (dy in -1..1) {
                    val yy = row + dy
                    if (yy !in 0 until rows) continue
                    for (dx in -1..1) {
                        val xx = column + dx
                        if (xx !in 0 until columns) continue
                        score += bins[yy * columns + xx]
                    }
                }
                if (score > 0) peaks += Peak(column, row, score)
                if (score > bestScore) bestScore = score
            }
        }
        if (bestScore == 0) return emptyList()

        val absoluteMinimum = maxOf(8, binSize * binSize / 20)
        val scoreThreshold = maxOf(
            absoluteMinimum,
            (bestScore * RELATIVE_PEAK_THRESHOLD).roundToInt(),
        )
        val minimumSeparationPx = maxOf(30.0, binSize * 2.35)
        val accepted = mutableListOf<Cluster>()

        for (peak in peaks.sortedByDescending { it.score }) {
            if (peak.score < scoreThreshold) break
            val seedX = (peak.column + 0.5) * binSize
            val seedY = (peak.row + 0.5) * binSize
            if (accepted.any { hypot(it.centerX - seedX, it.centerY - seedY) < minimumSeparationPx }) continue

            val refined = refineCluster(bitmap, mapBottom, palette, peak, binSize, width)
                ?: continue
            if (accepted.any { hypot(it.centerX - refined.centerX, it.centerY - refined.centerY) < minimumSeparationPx }) continue
            accepted += refined
            if (accepted.size >= maxMarkers) break
        }

        return accepted.sortedByDescending { it.score }
    }

    private fun refineCluster(
        bitmap: Bitmap,
        mapBottom: Int,
        palette: Palette,
        peak: Peak,
        binSize: Int,
        width: Int,
    ): Cluster? {
        val radius = maxOf(14, (binSize * 1.35).roundToInt())
        val seedX = ((peak.column + 0.5) * binSize).roundToInt()
        val seedY = ((peak.row + 0.5) * binSize).roundToInt()
        val left = maxOf(0, seedX - radius)
        val right = minOf(width - 1, seedX + radius)
        val top = maxOf(0, seedY - radius)
        val bottom = minOf(mapBottom - 1, seedY + radius)
        val rowPixels = IntArray(width)

        var count = 0L
        var sumX = 0L
        var sumY = 0L
        for (row in top..bottom) {
            bitmap.getPixels(rowPixels, 0, width, 0, row, width, 1)
            for (column in left..right) {
                if (!matches(rowPixels[column], palette)) continue
                count++
                sumX += column
                sumY += row
            }
        }
        val minimumPixels = maxOf(20, binSize * binSize / 8)
        if (count < minimumPixels) return null
        return Cluster(
            centerX = sumX.toDouble() / count,
            centerY = sumY.toDouble() / count,
            binSize = binSize,
            score = peak.score,
        )
    }

    private fun markerEvidence(
        bitmap: Bitmap,
        mapBottom: Int,
        palette: Palette,
        kind: BoltMarkerKind,
        clusters: List<Cluster>,
        duplicateTipDistancePx: Double,
        label: String,
    ): List<BoltMarkerEvidence> {
        if (clusters.isEmpty()) return emptyList()
        val strongest = clusters.first()
        val accepted = mutableListOf<Pair<ScreenPoint, Cluster>>()
        for (cluster in clusters) {
            val tip = pinTip(bitmap, mapBottom, palette, cluster)
            if (accepted.any { (existing, _) -> hypot(existing.x - tip.x, existing.y - tip.y) < duplicateTipDistancePx }) {
                continue
            }
            accepted += tip to cluster
        }
        return accepted.mapIndexed { index, (tip, cluster) ->
            BoltMarkerEvidence(
                kind = kind,
                screenCenter = tip,
                semanticLabel = "Bolt screenshot $label marker ${index + 1}",
                confidence = markerConfidence(cluster, strongest),
            )
        }
    }

    private fun markerConfidence(cluster: Cluster, strongest: Cluster): Double {
        val ratio = if (strongest.score <= 0) 0.0 else cluster.score.toDouble() / strongest.score
        return (0.66 + ratio.coerceIn(0.0, 1.0) * 0.10).coerceAtMost(0.76)
    }

    /**
     * Pickup/drop-off icons are pins, while the coloured route is only a thin line. Use the last
     * sufficiently wide marker row below the dense icon body as the geographic pin anchor.
     */
    private fun pinTip(
        bitmap: Bitmap,
        mapBottom: Int,
        palette: Palette,
        cluster: Cluster,
    ): ScreenPoint {
        val halfWidth = maxOf(10, cluster.binSize * 3 / 2)
        val left = maxOf(0, cluster.centerX.roundToInt() - halfWidth)
        val right = minOf(bitmap.width - 1, cluster.centerX.roundToInt() + halfWidth)
        val startY = (cluster.centerY.roundToInt() - cluster.binSize).coerceIn(0, mapBottom - 1)
        val endY = minOf(mapBottom - 1, cluster.centerY.roundToInt() + cluster.binSize * 4)
        val denseThreshold = maxOf(6, cluster.binSize / 3)
        val rowPixels = IntArray(bitmap.width)

        var lastDenseY = -1
        var lastDenseX = cluster.centerX
        for (y in startY..endY) {
            bitmap.getPixels(rowPixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            var count = 0
            var sumX = 0
            for (x in left..right) {
                if (!matches(rowPixels[x], palette)) continue
                count++
                sumX += x
            }
            if (count >= denseThreshold) {
                lastDenseY = y
                lastDenseX = sumX.toDouble() / count
            }
        }

        return if (lastDenseY >= 0) {
            ScreenPoint(lastDenseX, lastDenseY.toDouble())
        } else {
            ScreenPoint(cluster.centerX, cluster.centerY)
        }
    }

    private fun matches(color: Int, palette: Palette): Boolean {
        val red = color shr 16 and 0xff
        val green = color shr 8 and 0xff
        val blue = color and 0xff
        return when (palette) {
            Palette.GREEN ->
                red <= 100 && green in 105..195 && blue in 45..145 &&
                    green - red >= 45 && green - blue >= 20
            Palette.BLUE ->
                blue >= 180 && red in 45..160 && green in 45..165 &&
                    blue - red >= 55 && blue - green >= 55
            Palette.CYAN ->
                blue >= 170 && green >= 125 && red <= 145 &&
                    blue - red >= 55 && green - red >= 35 &&
                    blue - green in 10..100
        }
    }
}
