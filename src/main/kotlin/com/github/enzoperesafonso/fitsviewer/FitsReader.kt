package com.github.enzoperesafonso.fitsviewer

import nom.tam.fits.AsciiTableHDU
import nom.tam.fits.BasicHDU
import nom.tam.fits.BinaryTableHDU
import nom.tam.fits.Fits
import nom.tam.fits.Header
import nom.tam.fits.ImageHDU
import nom.tam.image.compression.hdu.CompressedImageHDU
import nom.tam.util.ArrayFuncs
import java.io.File
import kotlin.math.max
import kotlin.math.min

data class HeaderRow(val keyword: String, val value: String, val comment: String)

/**
 * Description of one HDU. Holds no pixel data, so one of these is kept for every HDU in the file
 * while only the plane being displayed is ever loaded into memory.
 */
class HduInfo(
    val index: Int,
    val label: String,
    val header: List<HeaderRow>,
    /** Image axes in FITS order (NAXIS1 first); empty for HDUs without image data. */
    val axes: IntArray,
    val wcs: Wcs?,
) {
    val isImage: Boolean get() = axes.size >= 2 && axes.all { it > 0 }
    val width: Int get() = axes[0]
    val height: Int get() = axes[1]

    /** Number of 2D planes (product of NAXIS3..NAXISn); 1 for a plain 2D image. */
    val planeCount: Int
        get() = if (!isImage) 0 else axes.drop(2).fold(1L) { acc, n -> acc * n }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    override fun toString(): String = label
}

class ScanResult(val hdus: List<HduInfo>, val warning: String?)

/** One 2D plane converted to physical values (BSCALE/BZERO applied, BLANK -> NaN), possibly downsampled. */
class FitsImage(
    /** Row-major; row 0 is the bottom row of the image, as in FITS. */
    val data: FloatArray,
    val width: Int,
    val height: Int,
    /** Downsampling factor: pixel (x, y) here is pixel (x * step, y * step) of the original plane. */
    val step: Int,
    val originalWidth: Int,
    val originalHeight: Int,
)

object FitsReader {
    /** Larger planes are downsampled while reading so one open file can't exhaust the IDE heap. */
    const val MAX_DISPLAY_PIXELS = 4096 * 4096

    /** Rows are read in bands of about this many pixels to bound temporary memory. */
    private const val BAND_PIXELS = 1 shl 20

    /** Reads every header in the file. Pixel data is not read for uncompressed files. */
    fun scan(file: File, checkCanceled: () -> Unit): ScanResult {
        val hdus = mutableListOf<HduInfo>()
        var warning: String? = null
        Fits(file).use { fits ->
            while (true) {
                checkCanceled()
                val hdu = try {
                    fits.readHDU() ?: break
                } catch (e: Exception) {
                    if (hdus.isEmpty()) throw e
                    warning = "Only the first ${hdus.size} HDU(s) could be read: ${e.message}"
                    break
                }
                hdus += describe(hdus.size, hdu)
            }
        }
        return ScanResult(hdus, warning)
    }

    private fun describe(index: Int, hdu: BasicHDU<*>): HduInfo {
        val header: Header
        val axes: IntArray
        val kind: String
        when (hdu) {
            is CompressedImageHDU -> {
                // Show the header of the image, not of the binary table that stores it
                header = runCatching { hdu.imageHeader }.getOrDefault(hdu.header)
                axes = hdu.imageAxes?.reversedArray() ?: IntArray(0)
                kind = "Compressed image"
            }
            is ImageHDU -> {
                header = hdu.header
                axes = hdu.axes?.reversedArray() ?: IntArray(0)
                kind = if (axes.isEmpty()) "No data" else "Image"
            }
            is BinaryTableHDU -> {
                header = hdu.header; axes = IntArray(0); kind = "Binary table"
            }
            is AsciiTableHDU -> {
                header = hdu.header; axes = IntArray(0); kind = "ASCII table"
            }
            else -> {
                header = hdu.header; axes = IntArray(0); kind = hdu.javaClass.simpleName.removeSuffix("HDU")
            }
        }

        val name = hdu.header.getStringValue("EXTNAME")?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (index == 0) "PRIMARY" else "HDU $index"
        val dims = if (axes.isEmpty()) "" else " " + axes.joinToString("×")

        val rows = ArrayList<HeaderRow>(header.numberOfCards)
        val cursor = header.iterator()
        while (cursor.hasNext()) {
            val card = cursor.next()
            rows += HeaderRow(card.key.orEmpty(), card.value.orEmpty(), card.comment.orEmpty())
        }

        val wcs = if (axes.size >= 2) runCatching { Wcs.fromHeader(header) }.getOrNull() else null
        return HduInfo(index, "[$index] $name · $kind$dims", rows, axes, wcs)
    }

    /**
     * Reads one 2D plane of an image HDU. Only that plane is read (in row bands) rather than the whole
     * data array, and planes with more than [maxPixels] pixels are downsampled by taking every n-th pixel.
     *
     * @param plane 0-based index over NAXIS3..NAXISn (0 for 2D images)
     */
    fun readPlane(
        file: File,
        info: HduInfo,
        plane: Int,
        maxPixels: Int = MAX_DISPLAY_PIXELS,
        checkCanceled: () -> Unit,
    ): FitsImage {
        require(info.isImage) { "HDU ${info.index} has no image data" }
        require(plane in 0 until info.planeCount) { "Plane $plane out of range" }

        val w = info.width
        val h = info.height
        var step = 1
        while (ceilDiv(w, step).toLong() * ceilDiv(h, step) > maxPixels) step++
        val outW = ceilDiv(w, step)
        val outH = ceilDiv(h, step)
        val out = FloatArray(outW * outH)

        Fits(file).use { fits ->
            var hdu = fits.getHDU(info.index)
            if (hdu is CompressedImageHDU && hasPartialTiles(hdu, info.axes)) {
                // nom-tam-fits 1.23 can't decompress a partial edge tile on its own (getTileHDU fails with
                // BufferUnderflowException), so decompress the whole image instead.
                checkCanceled()
                hdu = hdu.asImageHDU()
            }
            val naxis = info.axes.size
            // Tile coordinates are in Java order: slowest axis (NAXISn) first, NAXIS1 last
            val corners = IntArray(naxis)
            val lengths = IntArray(naxis) { 1 }
            var stride = 1
            for (k in 2 until naxis) {
                corners[naxis - 1 - k] = (plane / stride) % info.axes[k]
                stride *= info.axes[k]
            }
            lengths[naxis - 1] = w

            val scaling: Scaling
            val bandRows: Int
            val readBand: () -> Any
            when (hdu) {
                is CompressedImageHDU -> {
                    scaling = Scaling.from(hdu.imageHeader)
                    // Align bands with compression tiles so no tile is decompressed twice
                    val tileRows = max(1, hdu.header.getIntValue("ZTILE2", 1))
                    bandRows = tileRows * max(1L, BAND_PIXELS / (w.toLong() * tileRows)).toInt()
                    readBand = { ArrayFuncs.flatten(hdu.getTileHDU(corners, lengths).kernel) }
                }
                is ImageHDU -> {
                    scaling = Scaling.from(hdu.header)
                    // When downsampling, read only the rows we keep
                    bandRows = if (step > 1) 1 else max(1, BAND_PIXELS / w)
                    val tiler = hdu.tiler
                    var buffer: Any? = null
                    readBand = {
                        // Reuse the band buffer; only the last band may differ in size
                        val reusable = buffer?.takeIf { java.lang.reflect.Array.getLength(it) == lengths[naxis - 2] * w }
                        if (reusable != null) {
                            tiler.getTile(reusable, corners, lengths)
                            reusable
                        } else {
                            tiler.getTile(corners, lengths).also { buffer = it }
                        }
                    }
                }
                else -> error("HDU ${info.index} is not an image")
            }

            var y0 = 0
            while (y0 < h) {
                checkCanceled()
                val rows = min(bandRows, h - y0)
                val firstKept = ceilDiv(y0, step) * step
                if (firstKept < y0 + rows) {
                    corners[naxis - 2] = y0
                    lengths[naxis - 2] = rows
                    val band = readBand()
                    for (y in firstKept until y0 + rows step step) {
                        convertRow(band, (y - y0) * w, outW, step, out, (y / step) * outW, scaling)
                    }
                }
                y0 += rows
            }
        }
        return FitsImage(out, outW, outH, step, w, h)
    }

    private fun convertRow(src: Any, srcOffset: Int, count: Int, step: Int, dst: FloatArray, dstOffset: Int, s: Scaling) {
        when (src) {
            is FloatArray -> for (i in 0 until count) dst[dstOffset + i] = s.real(src[srcOffset + i * step].toDouble())
            is DoubleArray -> for (i in 0 until count) dst[dstOffset + i] = s.real(src[srcOffset + i * step])
            // BITPIX 8 is unsigned
            is ByteArray -> for (i in 0 until count) dst[dstOffset + i] = s.integer(src[srcOffset + i * step].toLong() and 0xFF)
            is ShortArray -> for (i in 0 until count) dst[dstOffset + i] = s.integer(src[srcOffset + i * step].toLong())
            is IntArray -> for (i in 0 until count) dst[dstOffset + i] = s.integer(src[srcOffset + i * step].toLong())
            is LongArray -> for (i in 0 until count) dst[dstOffset + i] = s.integer(src[srcOffset + i * step])
            else -> error("Unsupported pixel type ${src.javaClass.simpleName}")
        }
    }

    private class Scaling(private val scale: Double, private val zero: Double, private val hasBlank: Boolean, private val blank: Long) {
        fun real(v: Double): Float = (v * scale + zero).toFloat()
        fun integer(raw: Long): Float = if (hasBlank && raw == blank) Float.NaN else (raw * scale + zero).toFloat()

        companion object {
            fun from(header: Header) = Scaling(
                header.getDoubleValue("BSCALE", 1.0),
                header.getDoubleValue("BZERO", 0.0),
                header.containsKey("BLANK"),
                header.getLongValue("BLANK", 0L),
            )
        }
    }

    /** Whether some axis isn't a whole multiple of the tile size (default tiles are single rows). */
    private fun hasPartialTiles(hdu: CompressedImageHDU, axes: IntArray): Boolean = axes.indices.any { i ->
        val tile = hdu.header.getIntValue("ZTILE${i + 1}", if (i == 0) axes[0] else 1)
        tile > 0 && axes[i] % tile != 0
    }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b
}
