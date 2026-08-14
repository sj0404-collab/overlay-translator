package com.overlay.translator

import android.graphics.Bitmap
import kotlin.math.abs

/** Small difference hash used to skip unchanged live-capture frames. */
object PerceptualHash {
    fun of(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        return try {
            var result = 0L
            var bit = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    if (luma(scaled.getPixel(x, y)) > luma(scaled.getPixel(x + 1, y))) {
                        result = result or (1L shl bit)
                    }
                    bit++
                }
            }
            result
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun isSimilar(a: Long, b: Long, maxDistance: Int = 5): Boolean =
        a != 0L && b != 0L && distance(a, b) <= maxDistance

    private fun luma(color: Int): Int {
        val r = color shr 16 and 255
        val g = color shr 8 and 255
        val b = color and 255
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
