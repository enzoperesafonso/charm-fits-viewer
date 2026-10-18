package com.github.enzoperesafonso.fitsviewer

import java.awt.image.BufferedImage
import java.util.stream.IntStream
import kotlin.math.asinh
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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

/** Colour maps; the perceptual ones are sampled from matplotlib at 33 points (within 5/255 of the originals). */
enum class ColorMap(private val displayName: String, private val stops: IntArray) {
    GRAYSCALE("Grayscale", intArrayOf(0x000000, 0xFFFFFF)),
    HEAT("Heat", intArrayOf()),
    VIRIDIS(
        "Viridis",
        intArrayOf(
            0x440154, 0x470D60, 0x48186A, 0x482374, 0x472D7B, 0x453781, 0x424086, 0x3E4989,
            0x3B528B, 0x375B8D, 0x33638D, 0x2F6B8E, 0x2C728E, 0x297A8E, 0x26828E, 0x23898E,
            0x21918C, 0x1F988B, 0x1FA088, 0x22A785, 0x28AE80, 0x32B67A, 0x3FBC73, 0x4EC36B,
            0x5EC962, 0x70CF57, 0x84D44B, 0x98D83E, 0xADDC30, 0xC2DF23, 0xD8E219, 0xECE51B,
            0xFDE725,
        ),
    ),
    MAGMA(
        "Magma",
        intArrayOf(
            0x000004, 0x030312, 0x0A0822, 0x130D34, 0x1D1147, 0x29115A, 0x36106B, 0x440F76,
            0x51127C, 0x5D177F, 0x6A1C81, 0x762181, 0x832681, 0x902A81, 0x9C2E7F, 0xAA337D,
            0xB73779, 0xC43C75, 0xD0416F, 0xDC4869, 0xE75263, 0xEF5D5E, 0xF56B5C, 0xF9795D,
            0xFC8961, 0xFD9869, 0xFEA772, 0xFEB67C, 0xFEC488, 0xFED395, 0xFDE2A3, 0xFCF0B2,
            0xFCFDBF,
        ),
    ),
    INFERNO(
        "Inferno",
        intArrayOf(
            0x000004, 0x040312, 0x0B0724, 0x150B37, 0x210C4A, 0x2F0A5B, 0x3D0965, 0x4A0C6B,
            0x57106E, 0x64156E, 0x71196E, 0x7D1E6D, 0x8A226A, 0x972766, 0xA32C61, 0xB0315B,
            0xBC3754, 0xC73E4C, 0xD24644, 0xDB503B, 0xE45A31, 0xEB6628, 0xF1731D, 0xF68013,
            0xF98E09, 0xFB9D07, 0xFCAC11, 0xFBBC21, 0xF9CB35, 0xF5DB4C, 0xF2EA69, 0xF3F68A,
            0xFCFFA4,
        ),
    ),
    PLASMA(
        "Plasma",
        intArrayOf(
            0x0D0887, 0x220690, 0x310597, 0x3F049C, 0x4C02A1, 0x5901A5, 0x6600A7, 0x7201A8,
            0x7E03A8, 0x8A09A5, 0x9511A1, 0xA01A9C, 0xAA2395, 0xB32C8E, 0xBC3587, 0xC43E7F,
            0xCC4778, 0xD35171, 0xDA5A6A, 0xE06363, 0xE66C5C, 0xEB7655, 0xF0804E, 0xF58B47,
            0xF89540, 0xFBA139, 0xFDAC33, 0xFEB82C, 0xFDC527, 0xFCD225, 0xF8DF25, 0xF4ED27,
            0xF0F921,
        ),
    ),
    CIVIDIS(
        "Cividis",
        intArrayOf(
            0x00224E, 0x00285B, 0x002E6A, 0x053371, 0x1A386F, 0x273E6E, 0x32436D, 0x3B496C,
            0x434E6C, 0x4B546C, 0x535A6D, 0x5A5F6E, 0x61656F, 0x686A71, 0x6F7073, 0x767676,
            0x7D7C78, 0x848279, 0x8C8878, 0x938E78, 0x9B9476, 0xA39A74, 0xABA072, 0xB4A76F,
            0xBCAE6C, 0xC4B468, 0xCDBB63, 0xD5C25E, 0xDEC958, 0xE7D150, 0xF0D846, 0xF9E03A,
            0xFEE838,
        ),
    ),
    COOL(
        "Cool",
        intArrayOf(
            0x00FFFF, 0x08F7FF, 0x10EFFF, 0x18E7FF, 0x20DFFF, 0x28D7FF, 0x30CFFF, 0x38C7FF,
            0x40BFFF, 0x48B7FF, 0x50AFFF, 0x58A7FF, 0x609FFF, 0x6897FF, 0x708FFF, 0x7887FF,
            0x807FFF, 0x8877FF, 0x906FFF, 0x9867FF, 0xA05FFF, 0xA857FF, 0xB04FFF, 0xB847FF,
            0xC03FFF, 0xC837FF, 0xD02FFF, 0xD827FF, 0xE01FFF, 0xE817FF, 0xF00FFF, 0xF807FF,
            0xFF00FF,
        ),
    );

    /** Packed RGB for t in 0..1, linearly interpolated between the stops. */
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
        fun channel(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return (ca + (cb - ca) * f).roundToInt()
        }
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
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

    /** @param inverted swap the ends of the colour map, e.g. black stars on a white sky */
    fun buildLut(scale: ScaleMode, colorMap: ColorMap, inverted: Boolean = false): IntArray =
        IntArray(LUT_SIZE) { i ->
            val t = scale.apply(i / (LUT_SIZE - 1f)).coerceIn(0f, 1f)
            colorMap.rgb(if (inverted) 1f - t else t)
        }

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
