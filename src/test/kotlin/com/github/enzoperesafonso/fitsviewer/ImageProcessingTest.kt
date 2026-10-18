package com.github.enzoperesafonso.fitsviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.image.BufferedImage

class ImageProcessingTest {
    @Test
    fun `statistics ignore non-finite pixels`() {
        val data = FloatArray(102) { (it - 1).toFloat() }
        data[0] = Float.NaN
        data[101] = Float.POSITIVE_INFINITY
        val stats = ImageStatistics.compute(data)!!

        assertEquals(100, stats.finiteCount)
        assertEquals(0f, stats.min)
        assertEquals(99f, stats.max)
        assertEquals(49.5, stats.mean, 1e-9)
        assertEquals(28.866, stats.stdDev, 1e-3)
        assertEquals(0f, stats.percentile(0.0))
        assertEquals(99f, stats.percentile(100.0))
        assertEquals(2f, stats.percentile(2.0), 1f)
    }

    @Test
    fun `zscale matches astropy`() {
        // Reference: astropy.visualization.ZScaleInterval().get_limits(v) on the same float32 values
        val data = FloatArray(1000) { i ->
            (100 + 5 * kotlin.math.sin(i * 0.7) + 3 * kotlin.math.cos(i * 1.3) + if (i % 37 == 0) 2000.0 else 0.0).toFloat()
        }
        val (lo, hi) = ImageStatistics.compute(data)!!.zscale
        assertEquals(92.02867, lo.toDouble(), 1e-3)
        assertEquals(128.96000, hi.toDouble(), 1e-3)

        // A uniform ramp has no outliers: astropy returns the full range
        val ramp = ImageStatistics.compute(FloatArray(1000) { it.toFloat() })!!.zscale
        assertEquals(0f to 999f, ramp)
    }

    @Test
    fun `statistics of an image without finite pixels are null`() {
        assertNull(ImageStatistics.compute(floatArrayOf(Float.NaN, Float.NaN)))
    }

    @Test
    fun `percentiles of large images come from a bounded sample`() {
        val n = 3_000_000
        val data = FloatArray(n) { it.toFloat() }
        val stats = ImageStatistics.compute(data)!!
        assertEquals(n * 0.02, stats.percentile(2.0).toDouble(), n * 0.001)
        assertEquals(n * 0.98, stats.percentile(98.0).toDouble(), n * 0.001)
    }

    @Test
    fun `histogram counts values inside the range`() {
        val h = Histogram.compute(floatArrayOf(0f, 0.5f, 1f, 2f, Float.NaN, -1f), 0f, 1f, bins = 2)
        assertEquals(listOf(1, 2), h.counts.toList())
    }

    @Test
    fun `histogram of a constant image has a non-zero range`() {
        val h = Histogram.compute(floatArrayOf(5f, 5f), 5f, 5f, bins = 4)
        assertEquals(2, h.counts.sum())
        assert(h.max > h.min)
    }

    @Test
    fun `renders bottom FITS row at the bottom of the image`() {
        val image = FitsImage(floatArrayOf(0f, 10f), width = 1, height = 2, step = 1, originalWidth = 1, originalHeight = 2)
        val target = BufferedImage(1, 2, BufferedImage.TYPE_INT_RGB)
        ImageRenderer.render(image, 0f, 10f, ImageRenderer.buildLut(ScaleMode.LINEAR, ColorMap.GRAYSCALE), target)

        assertEquals(0xFFFFFF, target.getRGB(0, 0) and 0xFFFFFF) // top: value 10
        assertEquals(0x000000, target.getRGB(0, 1) and 0xFFFFFF) // bottom: value 0
    }

    @Test
    fun `renders NaN and handles equal black and white levels`() {
        val image = FitsImage(floatArrayOf(Float.NaN, 4f, 6f), width = 3, height = 1, step = 1, originalWidth = 3, originalHeight = 1)
        val target = BufferedImage(3, 1, BufferedImage.TYPE_INT_RGB)
        ImageRenderer.render(image, 5f, 5f, ImageRenderer.buildLut(ScaleMode.LOG, ColorMap.GRAYSCALE), target)

        assertEquals(0x000000, target.getRGB(0, 0) and 0xFFFFFF)
        assertEquals(0x000000, target.getRGB(1, 0) and 0xFFFFFF)
        assertEquals(0xFFFFFF, target.getRGB(2, 0) and 0xFFFFFF)
    }

    @Test
    fun `every scale mode maps 0 and 1 to the ends of the colour map`() {
        for (mode in ScaleMode.entries) {
            val lut = ImageRenderer.buildLut(mode, ColorMap.GRAYSCALE)
            assertEquals(mode.name, 0x000000, lut.first())
            assertEquals(mode.name, 0xFFFFFF, lut.last())
        }
    }
}
