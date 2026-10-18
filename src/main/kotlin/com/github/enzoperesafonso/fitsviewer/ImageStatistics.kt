package com.github.enzoperesafonso.fitsviewer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Summary statistics over the finite pixels of an image. Percentiles come from a sorted, evenly
 * spaced sample of at most [SAMPLE_SIZE] pixels, so memory stays bounded for large images.
 */
class ImageStatistics private constructor(
    val min: Float,
    val max: Float,
    val mean: Double,
    val stdDev: Double,
    val finiteCount: Int,
    private val sortedSample: FloatArray,
) {
    /** @param percent in 0..100 */
    fun percentile(percent: Double): Float {
        val i = (percent / 100.0 * (sortedSample.size - 1)).roundToInt()
        return sortedSample[i.coerceIn(0, sortedSample.size - 1)]
    }

    companion object {
        const val SAMPLE_SIZE = 500_000

        /** Returns null if the image has no finite pixels. */
        fun compute(data: FloatArray): ImageStatistics? {
            var count = 0
            var min = Float.POSITIVE_INFINITY
            var max = Float.NEGATIVE_INFINITY
            var sum = 0.0
            for (v in data) {
                if (!v.isFinite()) continue
                count++
                sum += v
                if (v < min) min = v
                if (v > max) max = v
            }
            if (count == 0) return null

            val mean = sum / count
            var sumSq = 0.0
            for (v in data) {
                if (!v.isFinite()) continue
                val d = v - mean
                sumSq += d * d
            }

            val stride = (count + SAMPLE_SIZE - 1) / SAMPLE_SIZE
            val sample = FloatArray((count + stride - 1) / stride)
            var finiteIndex = 0
            var n = 0
            for (v in data) {
                if (!v.isFinite()) continue
                if (finiteIndex++ % stride == 0) sample[n++] = v
            }
            sample.sort()

            return ImageStatistics(min, max, mean, sqrt(sumSq / count), count, sample)
        }
    }
}

class Histogram(val counts: IntArray, val min: Float, val max: Float) {
    companion object {
        /** Histogram of the finite values in [lo, hi]; values outside the range are ignored. */
        fun compute(data: FloatArray, lo: Float, hi: Float, bins: Int = 256): Histogram {
            // Avoid a zero-width range for constant images
            val top = if (hi > lo) hi else lo + max(1f, abs(lo) * 1e-3f)
            val counts = IntArray(bins)
            val scale = bins / (top.toDouble() - lo)
            for (v in data) {
                if (v >= lo && v <= top) {
                    counts[((v - lo) * scale).toInt().coerceAtMost(bins - 1)]++
                }
            }
            return Histogram(counts, lo, top)
        }
    }
}
