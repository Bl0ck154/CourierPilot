package com.block154.courierpilot

import android.graphics.Bitmap

/**
 * Small perceptual fingerprint of the lower offer-card area.
 *
 * Bolt may repost one live offer under another notification key and OCR can disagree between frames.
 * The lower card itself is much more stable than map labels, so this is a final duplicate guard before
 * a second screenshot/DB row is allowed to survive.
 */
internal object OfferVisualFingerprint {
    private const val COLS = 9
    private const val ROWS = 8
    private const val BOTTOM_REGION_START = 0.50
    internal const val MAX_HAMMING_DISTANCE = 10

    fun fromBottomCard(bitmap: Bitmap): String? {
        if (bitmap.width < COLS || bitmap.height < ROWS) return null
        val startY = (bitmap.height * BOTTOM_REGION_START).toInt().coerceIn(0, bitmap.height - 1)
        val regionHeight = (bitmap.height - startY).coerceAtLeast(1)
        var bits = 0L
        var bit = 0
        for (row in 0 until ROWS) {
            val y = (startY + ((row + 0.5) * regionHeight / ROWS).toInt()).coerceIn(0, bitmap.height - 1)
            var previous = luminance(bitmap.getPixel(sampleX(0, bitmap.width), y))
            for (col in 1 until COLS) {
                val current = luminance(bitmap.getPixel(sampleX(col, bitmap.width), y))
                if (previous > current) bits = bits or (1L shl bit)
                previous = current
                bit++
            }
        }
        return java.lang.Long.toUnsignedString(bits, 16).padStart(16, '0')
    }

    fun isNear(first: String, second: String, maxDistance: Int = MAX_HAMMING_DISTANCE): Boolean {
        if (first.length != 16 || second.length != 16) return false
        val left = runCatching { java.lang.Long.parseUnsignedLong(first, 16) }.getOrNull() ?: return false
        val right = runCatching { java.lang.Long.parseUnsignedLong(second, 16) }.getOrNull() ?: return false
        return java.lang.Long.bitCount(left xor right) <= maxDistance
    }

    private fun sampleX(col: Int, width: Int): Int =
        (((col + 0.5) * width) / COLS).toInt().coerceIn(0, width - 1)

    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
