package com.github.enzoperesafonso.fitsviewer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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
    /** Noise estimate that ignores sources: 1.4826 × median absolute deviation (σ for Gaussian noise). */
    val robustSigma: Double,
    private val sortedSample: FloatArray,
) {
    val median: Float get() = percentile(50.0)

    /** @param percent in 0..100 */
    fun percentile(percent: Double): Float {
        val i = (percent / 100.0 * (sortedSample.size - 1)).roundToInt()
        return sortedSample[i.coerceIn(0, sortedSample.size - 1)]
    }

    /** Histogram of the pixel sample over [lo, hi]. Cheap enough to recompute on the UI thread. */
    fun histogram(lo: Float, hi: Float, bins: Int = 256): Histogram = Histogram.compute(sortedSample, lo, hi, bins)

    /** ZScale display limits, as in IRAF and astropy's `ZScaleInterval`. */
    val zscale: Pair<Float, Float> by lazy { computeZScale() }

    private fun computeZScale(): Pair<Float, Float> {
        // Fit a line to the sorted pixel values (rank vs value), rejecting outliers, and use its
        // slope scaled by 1/contrast around the median.
        val stride = max(1, sortedSample.size / ZSCALE_SAMPLES)
        val s = DoubleArray((sortedSample.size + stride - 1) / stride) { sortedSample[it * stride].toDouble() }
        val n = s.size
        val center = (n - 1) / 2
        val median = if (n % 2 == 1) s[center] else 0.5 * (s[center] + s[center + 1])
        val minPixels = max(5, (n * 0.5).toInt())
        val grow = max(1, (n * 0.01).toInt())

        val bad = BooleanArray(n)
        var good = n
        var lastGood = n + 1
        var slope = 0.0
        for (iteration in 0 until 5) {
            if (good >= lastGood || good < minPixels) break

            var sx = 0.0
            var sy = 0.0
            var sxx = 0.0
            var sxy = 0.0
            for (i in 0 until n) {
                if (bad[i]) continue
                sx += i
                sy += s[i]
                sxx += i.toDouble() * i
                sxy += i * s[i]
            }
            val denominator = good * sxx - sx * sx
            if (denominator == 0.0) break
            slope = (good * sxy - sx * sy) / denominator
            val intercept = (sy - slope * sx) / good

            var sum = 0.0
            var sumSq = 0.0
            for (i in 0 until n) {
                if (bad[i]) continue
                val r = s[i] - (intercept + slope * i)
                sum += r
                sumSq += r * r
            }
            val mean = sum / good
            val threshold = 2.5 * sqrt(max(0.0, sumSq / good - mean * mean))

            val rejected = BooleanArray(n) { bad[it] || abs(s[it] - (intercept + slope * it)) > threshold }
            // Also reject the neighbours of rejected points
            for (i in 0 until n) {
                if (!rejected[i]) continue
                for (j in max(0, i - grow / 2)..min(n - 1, i + (grow - 1) / 2)) bad[j] = true
            }
            lastGood = good
            good = bad.count { !it }
        }

        var lo = s[0]
        var hi = s[n - 1]
        if (good >= minPixels) {
            val scaled = slope / ZSCALE_CONTRAST
            lo = max(lo, median - (center - 1) * scaled)
            hi = min(hi, median + (n - center) * scaled)
        }
        return lo.toFloat() to hi.toFloat()
    }

    companion object {
        const val SAMPLE_SIZE = 500_000
        private const val ZSCALE_SAMPLES = 1000
        private const val ZSCALE_CONTRAST = 0.25
        private const val MAD_TO_SIGMA = 1.4826

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

            val median = sample[(sample.size - 1) / 2]
            val deviations = FloatArray(sample.size) { abs(sample[it] - median) }
            deviations.sort()
            val robustSigma = MAD_TO_SIGMA * deviations[(deviations.size - 1) / 2]

            return ImageStatistics(min, max, mean, sqrt(sumSq / count), count, robustSigma, sample)
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
