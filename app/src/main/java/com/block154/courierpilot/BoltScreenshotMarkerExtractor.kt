package com.block154.courierpilot

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Lightweight Bolt/Mapbox marker recovery from the clean offer screenshot.
 *
 * Recent Bolt builds expose essentially an empty Accessibility subtree for the map, so marker
 * coordinates have to come from pixels. This detector intentionally looks only for Bolt's stable
 * marker palette and local marker-sized density; it does not OCR or guess merchant/customer names.
 * Thin route polylines do not have enough local density to win a marker candidate.
 */
internal object BoltScreenshotMarkerExtractor {
    private const val MAP_BOTTOM_FRACTION = 0.72
    private const val SAMPLE_STEP = 2

    fun extract(bitmap: Bitmap): BoltSemanticMarkers? {
        if (bitmap.width < 200 || bitmap.height < 300) return null
        val mapBottom = (bitmap.height * MAP_BOTTOM_FRACTION).roundToInt()
            .coerceIn(1, bitmap.height)

        val currentCluster = findCluster(bitmap, mapBottom, Palette.CYAN) ?: return null
        val pickupCluster = findCluster(bitmap, mapBottom, Palette.BLUE) ?: return null
        val dropoffCluster = findCluster(bitmap, mapBottom, Palette.GREEN) ?: return null

        val currentPoint = ScreenPoint(currentCluster.centerX, currentCluster.centerY)
        val pickupPoint = pinTip(bitmap, mapBottom, Palette.BLUE, pickupCluster)
        val dropoffPoint = pinTip(bitmap, mapBottom, Palette.GREEN, dropoffCluster)

        return BoltSemanticMarkers(
            currentLocation = BoltMarkerEvidence(
                kind = BoltMarkerKind.CURRENT_LOCATION,
                screenCenter = currentPoint,
                semanticLabel = "Bolt screenshot current-location marker",
                confidence = 0.74,
            ),
            pickup = BoltMarkerEvidence(
                kind = BoltMarkerKind.PICKUP,
                screenCenter = pickupPoint,
                semanticLabel = "Bolt screenshot pickup marker",
                confidence = 0.76,
            ),
            dropoff = BoltMarkerEvidence(
                kind = BoltMarkerKind.DROPOFF,
                screenCenter = dropoffPoint,
                semanticLabel = "Bolt screenshot customer marker",
                confidence = 0.76,
            ),
            unknown = emptyList(),
        )
    }

    fun looksLikeOfferMap(bitmap: Bitmap): Boolean = extract(bitmap) != null

    private enum class Palette { GREEN, BLUE, CYAN }

    private data class Cluster(
        val centerX: Double,
        val centerY: Double,
        val binSize: Int,
    )

    private fun findCluster(bitmap: Bitmap, mapBottom: Int, palette: Palette): Cluster? {
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

        var bestScore = 0
        var bestColumn = -1
        var bestRow = -1
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
                if (score > bestScore) {
                    bestScore = score
                    bestColumn = column
                    bestRow = row
                }
            }
        }

        val minimumScore = maxOf(18, binSize * binSize / 9)
        if (bestColumn < 0 || bestScore < minimumScore) return null

        val left = maxOf(0, (bestColumn - 2) * binSize)
        val right = minOf(width, (bestColumn + 3) * binSize)
        val top = maxOf(0, (bestRow - 2) * binSize)
        val bottom = minOf(mapBottom, (bestRow + 3) * binSize)

        var count = 0L
        var sumX = 0L
        var sumY = 0L
        for (row in top until bottom) {
            bitmap.getPixels(pixels, 0, width, 0, row, width, 1)
            for (column in left until right) {
                if (!matches(pixels[column], palette)) continue
                count++
                sumX += column
                sumY += row
            }
        }
        if (count < minimumScore) return null
        return Cluster(
            centerX = sumX.toDouble() / count,
            centerY = sumY.toDouble() / count,
            binSize = binSize,
        )
    }

    /**
     * Pickup/drop-off icons are pins, while the colored route is only a thin line. Use the last
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
        val startY = cluster.centerY.roundToInt().coerceIn(0, mapBottom - 1)
        val endY = minOf(mapBottom - 1, startY + cluster.binSize * 3)
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
