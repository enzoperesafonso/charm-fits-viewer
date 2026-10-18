package com.github.enzoperesafonso.fitsviewer

import nom.tam.fits.Header
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class WcsTest {
    private fun header(vararg cards: Pair<String, Any>) = Header().apply {
        cards.forEach { (key, value) ->
            when (value) {
                is String -> addValue(key, value, "")
                is Number -> addValue(key, value.toDouble(), "")
            }
        }
    }

    private fun tan(ra0: Double, dec0: Double, scale: Double = 1.0 / 3600) = Wcs.fromHeader(
        header(
            "CTYPE1" to "RA---TAN", "CTYPE2" to "DEC--TAN",
            "CRPIX1" to 50.0, "CRPIX2" to 50.0,
            "CRVAL1" to ra0, "CRVAL2" to dec0,
            "CD1_1" to -scale, "CD2_2" to scale,
        ),
    )!!

    @Test
    fun `reference pixel maps to CRVAL`() {
        val world = tan(150.0, 2.2).pixelToWorld(49.0, 49.0) // CRPIX is 1-based
        assertEquals(150.0, world[0], 1e-9)
        assertEquals(2.2, world[1], 1e-9)
    }

    @Test
    fun `TAN matches the closed-form gnomonic inverse`() {
        val ra0 = 210.0
        val dec0 = 54.0
        val wcs = tan(ra0, dec0, scale = 0.01)
        for ((x, y) in listOf(0.0 to 0.0, 300.0 to -120.0, -500.0 to 800.0, 49.0 to 1000.0)) {
            val xi = Math.toRadians(-0.01 * (x + 1 - 50))
            val eta = Math.toRadians(0.01 * (y + 1 - 50))
            val d0 = Math.toRadians(dec0)
            val denom = cos(d0) - eta * sin(d0)
            val expectedRa = (ra0 + Math.toDegrees(atan2(xi, denom)) + 360) % 360
            val expectedDec = Math.toDegrees(atan2(eta * cos(d0) + sin(d0), sqrt(xi * xi + denom * denom)))

            val world = wcs.pixelToWorld(x, y)
            assertEquals("RA at ($x, $y)", expectedRa, world[0], 1e-8)
            assertEquals("Dec at ($x, $y)", expectedDec, world[1], 1e-8)
        }
    }

    @Test
    fun `CDELT with CROTA2 is rotated`() {
        val wcs = Wcs.fromHeader(
            header(
                "CTYPE1" to "RA---TAN", "CTYPE2" to "DEC--TAN",
                "CRPIX1" to 1.0, "CRPIX2" to 1.0, "CRVAL1" to 0.0, "CRVAL2" to 0.0,
                "CDELT1" to -0.001, "CDELT2" to 0.001, "CROTA2" to 90.0,
            ),
        )!!
        // With a 90° rotation, moving along +x moves in declination
        val world = wcs.pixelToWorld(100.0, 0.0)
        assertEquals(0.0, (world[0] + 180) % 360 - 180, 1e-6)
        assertEquals(-0.1, world[1], 1e-4)
    }

    @Test
    fun `non-celestial axes are linear`() {
        val wcs = Wcs.fromHeader(
            header("CTYPE1" to "WAVE", "CTYPE2" to "TIME", "CRPIX1" to 1.0, "CRPIX2" to 1.0, "CRVAL1" to 5000.0, "CRVAL2" to 0.0, "CDELT1" to 2.0, "CDELT2" to 0.5),
        )!!
        assertFalse(wcs.isCelestial)
        val world = wcs.pixelToWorld(10.0, 4.0)
        assertEquals(5020.0, world[0], 1e-9)
        assertEquals(2.0, world[1], 1e-9)
    }

    @Test
    fun `headers without WCS give null`() {
        assertNull(Wcs.fromHeader(header("NAXIS" to 2)))
        assertNull(Wcs.fromHeader(header("CDELT1" to 1.0, "CDELT2" to 1.0)))
        assertNotNull(Wcs.fromHeader(header("CTYPE1" to "RA---SIN", "CTYPE2" to "DEC--SIN", "CDELT1" to 1.0)))
    }

    @Test
    fun `sexagesimal formatting rounds without overflowing`() {
        assertEquals("01h 00m 00.00s", Wcs.formatRa(15.0))
        assertEquals("00h 00m 00.00s", Wcs.formatRa(359.99999999))
        assertEquals("00h 00m 00.00s", Wcs.formatRa(-0.00000001))
        assertEquals("+10° 00' 00.00\"", Wcs.formatDec(9.9999999999))
        assertEquals("-00° 30' 00.00\"", Wcs.formatDec(-0.5))
    }

    @Test
    fun `angular separation accounts for declination`() {
        assertEquals(1.0, Wcs.angularSeparation(doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 0.0)), 1e-9)
        assertEquals(0.5, Wcs.angularSeparation(doubleArrayOf(0.0, 60.0), doubleArrayOf(1.0, 60.0)), 1e-3)
        assertTrue(Wcs.angularSeparation(doubleArrayOf(359.9, 0.0), doubleArrayOf(0.1, 0.0)) < 0.2 + 1e-9)
    }
}
