package com.github.enzoperesafonso.fitsviewer

import nom.tam.fits.BasicHDU
import nom.tam.fits.Fits
import nom.tam.fits.ImageHDU
import nom.tam.fits.NullDataHDU
import nom.tam.fits.header.Compression
import nom.tam.image.compression.hdu.CompressedImageHDU
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.GZIPOutputStream

class FitsReaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val noCancel: () -> Unit = {}

    private fun write(vararg hdus: BasicHDU<*>): File {
        val file = tmp.newFile()
        Fits().use { fits ->
            hdus.forEach { fits.addHDU(it) }
            fits.write(file)
        }
        return file
    }

    private fun image(data: Any, vararg cards: Pair<String, Number>): ImageHDU {
        val hdu = Fits.makeHDU(data) as ImageHDU
        cards.forEach { (key, value) ->
            when (value) {
                is Int -> hdu.header.addValue(key, value, "")
                else -> hdu.header.addValue(key, value.toDouble(), "")
            }
        }
        return hdu
    }

    private fun readOnly(file: File, plane: Int = 0, maxPixels: Int = FitsReader.MAX_DISPLAY_PIXELS): FitsImage {
        val hdu = FitsReader.scan(file, noCancel).hdus.first { it.isImage }
        return FitsReader.readPlane(file, hdu, plane, maxPixels, noCancel)
    }

    @Test
    fun `applies BZERO to unsigned 16-bit data and keeps FITS row order`() {
        val raw = Array(3) { y -> ShortArray(4) { x -> (y * 4 + x - 32768).toShort() } }
        val img = readOnly(write(image(raw, "BZERO" to 32768)))

        assertEquals(4, img.width)
        assertEquals(3, img.height)
        assertEquals(1, img.step)
        for (y in 0 until 3) for (x in 0 until 4) {
            assertEquals((y * 4 + x).toFloat(), img.data[y * 4 + x], 0f)
        }
    }

    @Test
    fun `treats 8-bit data as unsigned`() {
        val raw = arrayOf(byteArrayOf(0, 127, -128, -1))
        val img = readOnly(write(image(raw)))
        assertEquals(listOf(0f, 127f, 128f, 255f), img.data.toList())
    }

    @Test
    fun `maps BLANK to NaN and applies BSCALE`() {
        val raw = arrayOf(intArrayOf(1, -999, 3))
        val img = readOnly(write(image(raw, "BLANK" to -999, "BSCALE" to 0.5)))
        assertEquals(0.5f, img.data[0], 0f)
        assertTrue(img.data[1].isNaN())
        assertEquals(1.5f, img.data[2], 0f)
    }

    @Test
    fun `reads a single plane of a cube`() {
        val cube = Array(3) { z -> Array(4) { y -> FloatArray(5) { x -> z * 100f + y * 10f + x } } }
        val file = write(image(cube))
        val hdu = FitsReader.scan(file, noCancel).hdus.single()
        assertEquals(3, hdu.planeCount)
        assertEquals(listOf(5, 4, 3), hdu.axes.toList())

        val img = FitsReader.readPlane(file, hdu, 1, checkCanceled = noCancel)
        assertEquals(5, img.width)
        assertEquals(4, img.height)
        for (y in 0 until 4) for (x in 0 until 5) {
            assertEquals(100f + y * 10f + x, img.data[y * 5 + x], 0f)
        }
    }

    @Test
    fun `downsamples planes larger than the pixel budget`() {
        val raw = Array(60) { y -> IntArray(100) { x -> y * 1000 + x } }
        val img = readOnly(write(image(raw)), maxPixels = 1000)

        assertEquals(3, img.step)
        assertEquals(34, img.width)
        assertEquals(20, img.height)
        assertEquals(100, img.originalWidth)
        assertEquals(60, img.originalHeight)
        for (oy in 0 until img.height) for (ox in 0 until img.width) {
            assertEquals((oy * 3 * 1000 + ox * 3).toFloat(), img.data[oy * img.width + ox], 0f)
        }
    }

    private fun writeCompressed(width: Int, height: Int, tileRows: Int): File {
        val raw = Array(height) { y -> ShortArray(width) { x -> (y * width + x).toShort() } }
        val compressed = CompressedImageHDU.fromImageHDU(image(raw), width, tileRows)
            .setCompressAlgorithm(Compression.ZCMPTYPE_RICE_1)
        compressed.compress()
        return write(NullDataHDU(), compressed)
    }

    @Test
    fun `reads tile-compressed images`() {
        // 36 rows: whole 4-row tiles, read band by band. 37 rows: partial last tile, decompressed in one go.
        for (height in listOf(36, 37)) {
            val file = writeCompressed(23, height, tileRows = 4)
            val hdus = FitsReader.scan(file, noCancel).hdus
            assertEquals(2, hdus.size)
            assertFalse(hdus[0].isImage)
            assertTrue(hdus[1].label, hdus[1].label.contains("Compressed image 23×$height"))

            val img = FitsReader.readPlane(file, hdus[1], 0, checkCanceled = noCancel)
            for (i in 0 until 23 * height) assertEquals("height $height, pixel $i", i.toFloat(), img.data[i], 0f)

            val small = FitsReader.readPlane(file, hdus[1], 0, maxPixels = 100, checkCanceled = noCancel)
            val s = small.step
            assertTrue(small.width * small.height <= 100)
            // Pixel (1, 1) of the downsampled image is pixel (s, s) of the original
            assertEquals((s * 23 + s).toFloat(), small.data[small.width + 1], 0f)
        }
    }

    @Test
    fun `reads gzip-compressed files`() {
        val plain = write(image(arrayOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f))))
        val gz = tmp.newFile("image.fits")
        GZIPOutputStream(gz.outputStream()).use { out -> plain.inputStream().use { it.copyTo(out) } }
        assertEquals(listOf(1f, 2f, 3f, 4f), readOnly(gz).data.toList())
    }

    @Test
    fun `describes every HDU`() {
        val sci = image(arrayOf(floatArrayOf(1f)))
        sci.header.addValue("EXTNAME", "SCI", "")
        val table = Fits.makeHDU(arrayOf<Any>(intArrayOf(1, 2, 3), doubleArrayOf(1.0, 2.0, 3.0)))
        val result = FitsReader.scan(write(NullDataHDU(), sci, table), noCancel)

        assertNull(result.warning)
        val (primary, image, tab) = result.hdus
        assertEquals("[0] PRIMARY · No data", primary.label)
        assertEquals("[1] SCI · Image 1×1", image.label)
        assertTrue(tab.label, tab.label.endsWith("Binary table"))
        assertTrue(image.isImage)
        assertFalse(tab.isImage)
        assertTrue(image.header.any { it.keyword == "EXTNAME" && it.value == "SCI" })
    }

    @Test
    fun `keeps readable HDUs of a truncated file`() {
        val file = write(image(arrayOf(floatArrayOf(1f))), image(Array(100) { FloatArray(100) }))
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(2880 * 3)) // first HDU + header of the second, cut inside its data

        val result = FitsReader.scan(file, noCancel)
        assertTrue(result.hdus.isNotEmpty())
    }
}
