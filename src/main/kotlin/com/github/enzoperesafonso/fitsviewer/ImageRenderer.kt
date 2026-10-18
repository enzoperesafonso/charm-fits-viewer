package com.github.enzoperesafonso.fitsviewer

import java.awt.image.BufferedImage
import java.util.stream.IntStream
import kotlin.math.asinh
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class ScaleMode(private val displayName: String) {
    LINEAR("Linear"),
    LOG("Log"),
    SQRT("Square root"),
    ASINH("Asinh");

    /** Maps a normalised value in 0..1 to 0..1. */
    fun apply(t: Float): Float = when (this) {
        LINEAR -> t
        LOG -> ln(1f + t * 100f) / ln(101f)
        SQRT -> sqrt(t)
        ASINH -> asinh(t / ASINH_SOFTENING) / asinh(1f / ASINH_SOFTENING)
    }

    override fun toString() = displayName
}

private const val ASINH_SOFTENING = 0.05f

enum class ColorMap(private val displayName: String, private val stops: Array<IntArray>) {
    GRAYSCALE("Grayscale", arrayOf(intArrayOf(0, 0, 0), intArrayOf(255, 255, 255))),
    HEAT("Heat", emptyArray()),
    VIRIDIS(
        "Viridis",
        arrayOf(intArrayOf(68, 1, 84), intArrayOf(59, 82, 139), intArrayOf(33, 145, 140), intArrayOf(94, 201, 98), intArrayOf(253, 231, 37)),
    ),
    MAGMA(
        "Magma",
        arrayOf(intArrayOf(0, 0, 4), intArrayOf(81, 18, 124), intArrayOf(182, 54, 121), intArrayOf(251, 136, 97), intArrayOf(252, 253, 191)),
    );

    /** RGB for t in 0..1, linearly interpolated between the stops. */
    fun rgb(t: Float): Int {
        if (this == HEAT) {
            val r = (min(1f, t * 2f) * 255).toInt()
            val g = (max(0f, t * 2f - 1f) * 255).toInt()
            val b = (max(0f, t * 4f - 3f) * 255).toInt()
            return (r shl 16) or (g shl 8) or b
        }
        val pos = t.coerceIn(0f, 1f) * (stops.size - 1)
        val i = min(stops.size - 2, pos.toInt())
        val f = pos - i
        val a = stops[i]
        val b = stops[i + 1]
        val r = (a[0] + (b[0] - a[0]) * f).toInt()
        val g = (a[1] + (b[1] - a[1]) * f).toInt()
        val bl = (a[2] + (b[2] - a[2]) * f).toInt()
        return (r shl 16) or (g shl 8) or bl
    }

    override fun toString() = displayName
}

/**
 * Converts pixel values to colours. The stretch and colour map are baked into a lookup table once,
 * so rendering a pixel is a subtraction, a multiply and an array read.
 */
object ImageRenderer {
    private const val LUT_SIZE = 4096
    private const val ROWS_PER_TASK = 64

    fun buildLut(scale: ScaleMode, colorMap: ColorMap): IntArray =
        IntArray(LUT_SIZE) { i -> colorMap.rgb(scale.apply(i / (LUT_SIZE - 1f)).coerceIn(0f, 1f)) }

    /**
     * Renders [image] into [target] (same size, TYPE_INT_RGB), flipping vertically so the first
     * FITS row ends up at the bottom. NaN pixels get the colour of the black level.
     */
    fun render(image: FitsImage, black: Float, white: Float, lut: IntArray, target: BufferedImage) {
        val w = image.width
        val h = image.height
        val data = image.data
        val maxIndex = lut.size - 1
        val k = if (white > black) maxIndex / (white.toDouble() - black) else 0.0
        val raster = target.raster

        // Writing through setDataElements (instead of grabbing the DataBuffer array) keeps the image
        // "managed", so Java2D can cache it on the GPU between repaints.
        IntStream.range(0, (h + ROWS_PER_TASK - 1) / ROWS_PER_TASK).parallel().forEach { chunk ->
            val row = IntArray(w)
            for (y in chunk * ROWS_PER_TASK until min(h, (chunk + 1) * ROWS_PER_TASK)) {
                val base = y * w
                for (x in 0 until w) {
                    val v = data[base + x]
                    row[x] = when {
                        v.isNaN() -> lut[0]
                        k > 0.0 -> lut[max(0, min(maxIndex, ((v - black) * k).toInt()))]
                        else -> if (v >= white) lut[maxIndex] else lut[0]
                    }
                }
                raster.setDataElements(0, h - 1 - y, w, 1, row)
            }
        }
    }
}
