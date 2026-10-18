package com.github.enzoperesafonso.fitsviewer

import nom.tam.fits.Header
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * World coordinates for the first two image axes: the linear part (CD, PC + CDELT, or CDELT + CROTA2)
 * followed, for celestial axes, by one of the zenithal projections TAN, SIN, ARC, ZEA or STG.
 * Distortion terms such as SIP are ignored, which is fine for a cursor readout.
 */
class Wcs(
    val ctype1: String,
    val ctype2: String,
    private val crpix1: Double,
    private val crpix2: Double,
    private val crval1: Double,
    private val crval2: Double,
    private val cd: DoubleArray,
    private val lonPole: Double,
) {
    val isCelestial: Boolean = isLongitude(ctype1) && isLatitude(ctype2)
    private val projection = if (ctype1.length >= 8) ctype1.substring(5, 8) else ""

    /** World coordinates (degrees for celestial axes) of a 0-based pixel position. */
    fun pixelToWorld(x: Double, y: Double): DoubleArray {
        // FITS pixel coordinates are 1-based
        val dx = x + 1.0 - crpix1
        val dy = y + 1.0 - crpix2
        val ix = cd[0] * dx + cd[1] * dy
        val iy = cd[2] * dx + cd[3] * dy
        if (!isCelestial) return doubleArrayOf(crval1 + ix, crval2 + iy)
        return deproject(ix, iy) ?: doubleArrayOf(normalizeLon(crval1 + ix / cos(Math.toRadians(crval2))), crval2 + iy)
    }

    /** Zenithal deprojection (Calabretta & Greisen 2002). Returns null for unsupported projections. */
    private fun deproject(ix: Double, iy: Double): DoubleArray? {
        val r = hypot(ix, iy)
        val thetaDeg = when (projection) {
            "TAN" -> Math.toDegrees(atan2(180.0 / PI, r))
            "SIN" -> Math.toDegrees(acos((r * PI / 180.0).takeIf { it <= 1.0 } ?: return null))
            "ARC" -> 90.0 - r
            "ZEA" -> 90.0 - 2.0 * Math.toDegrees(asin((r * PI / 360.0).takeIf { it <= 1.0 } ?: return null))
            "STG" -> 90.0 - 2.0 * Math.toDegrees(atan(r * PI / 360.0))
            else -> return null
        }
        val phi = Math.toRadians(Math.toDegrees(atan2(ix, -iy)) - lonPole)
        val theta = Math.toRadians(thetaDeg)
        val dp = Math.toRadians(crval2)

        val dec = asin((sin(theta) * sin(dp) + cos(theta) * cos(dp) * cos(phi)).coerceIn(-1.0, 1.0))
        val dRa = atan2(-cos(theta) * sin(phi), sin(theta) * cos(dp) - cos(theta) * sin(dp) * cos(phi))
        return doubleArrayOf(normalizeLon(crval1 + Math.toDegrees(dRa)), Math.toDegrees(dec))
    }

    fun format(world: DoubleArray): String = when {
        isCelestial && ctype1.startsWith("RA") -> "RA ${formatRa(world[0])}  Dec ${formatDec(world[1])}"
        isCelestial -> "%s %.5f°  %s %.5f°".format(Locale.ROOT, axisName(ctype1), world[0], axisName(ctype2), world[1])
        else -> "%s %.6g  %s %.6g".format(Locale.ROOT, axisName(ctype1), world[0], axisName(ctype2), world[1])
    }

    companion object {
        /** Returns null if the header has no usable WCS for axes 1 and 2. */
        fun fromHeader(h: Header): Wcs? {
            val ctype1 = h.getStringValue("CTYPE1")?.trim().orEmpty()
            val ctype2 = h.getStringValue("CTYPE2")?.trim().orEmpty()
            val hasCd = listOf("CD1_1", "CD1_2", "CD2_1", "CD2_2").any { h.containsKey(it) }
            val hasCdelt = h.containsKey("CDELT1") || h.containsKey("CDELT2")
            if (!hasCd && !hasCdelt) return null
            if (ctype1.isEmpty() && ctype2.isEmpty()) return null

            val cd = if (hasCd) {
                doubleArrayOf(
                    h.getDoubleValue("CD1_1", 0.0), h.getDoubleValue("CD1_2", 0.0),
                    h.getDoubleValue("CD2_1", 0.0), h.getDoubleValue("CD2_2", 0.0),
                )
            } else {
                val c1 = h.getDoubleValue("CDELT1", 1.0)
                val c2 = h.getDoubleValue("CDELT2", 1.0)
                val hasPc = listOf("PC1_1", "PC1_2", "PC2_1", "PC2_2").any { h.containsKey(it) }
                if (hasPc) {
                    doubleArrayOf(
                        c1 * h.getDoubleValue("PC1_1", 1.0), c1 * h.getDoubleValue("PC1_2", 0.0),
                        c2 * h.getDoubleValue("PC2_1", 0.0), c2 * h.getDoubleValue("PC2_2", 1.0),
                    )
                } else {
                    val rho = Math.toRadians(h.getDoubleValue("CROTA2", 0.0))
                    doubleArrayOf(c1 * cos(rho), -c2 * sin(rho), c1 * sin(rho), c2 * cos(rho))
                }
            }

            val crval2 = h.getDoubleValue("CRVAL2", 0.0)
            // Default LONPOLE for zenithal projections (native latitude of the reference point is 90°)
            val lonPole = h.getDoubleValue("LONPOLE", if (crval2 >= 90.0) 0.0 else 180.0)
            return Wcs(
                ctype1, ctype2,
                h.getDoubleValue("CRPIX1", 0.0), h.getDoubleValue("CRPIX2", 0.0),
                h.getDoubleValue("CRVAL1", 0.0), crval2,
                cd, lonPole,
            )
        }

        /** Great-circle distance in degrees between two (lon, lat) positions given in degrees. */
        fun angularSeparation(a: DoubleArray, b: DoubleArray): Double {
            val lat1 = Math.toRadians(a[1])
            val lat2 = Math.toRadians(b[1])
            val sinDLat = sin((lat2 - lat1) / 2)
            val sinDLon = sin(Math.toRadians(b[0] - a[0]) / 2)
            val hav = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
            return Math.toDegrees(2 * asin(sqrt(hav.coerceIn(0.0, 1.0))))
        }

        fun formatRa(degrees: Double): String {
            // Round once to hundredths of a second so we never print "60.00s"
            val total = (normalizeLon(degrees) / 15.0 * 360_000.0).roundToLong() % (24L * 360_000)
            return "%02dh %02dm %05.2fs".format(Locale.ROOT, total / 360_000, total / 6_000 % 60, total % 6_000 / 100.0)
        }

        fun formatDec(degrees: Double): String {
            val total = (abs(degrees) * 360_000.0).roundToLong()
            val sign = if (degrees < 0) "-" else "+"
            return "%s%02d° %02d' %05.2f\"".format(Locale.ROOT, sign, total / 360_000, total / 6_000 % 60, total % 6_000 / 100.0)
        }

        private fun normalizeLon(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0

        private fun axisName(ctype: String) = ctype.substringBefore('-').ifEmpty { "?" }

        private fun isLongitude(ctype: String) =
            ctype.startsWith("RA") || ctype.length >= 4 && ctype[0] in "GESH" && ctype.substring(1, 4) == "LON"

        private fun isLatitude(ctype: String) =
            ctype.startsWith("DEC") || ctype.length >= 4 && ctype[0] in "GESH" && ctype.substring(1, 4) == "LAT"
    }
}
