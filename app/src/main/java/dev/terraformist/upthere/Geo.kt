package dev.terraformist.upthere

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** A distance split into the dotted number and its unit: "8,930" + "KM". */
data class Distance(val number: String, val unit: String)

/** Distances, directions and the words for them. No Android code. */
object Geo {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance in metres. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Starting direction of the shortest path, degrees clockwise from true north (0..360). */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Whole kilometres with commas ("8,930"); one decimal under 10 km ("4.2"); metres under 1 km. */
    fun format(meters: Double): Distance = when {
        meters < 995 -> Distance((((meters / 10).roundToInt()) * 10).toString(), "M")
        meters < 9_950 -> Distance(String.format(Locale.US, "%.1f", meters / 1000), "KM")
        else -> Distance(NumberFormat.getIntegerInstance(Locale.US).format((meters / 1000).roundToLong()), "KM")
    }

    /** Sixteen compass points: N, NNE, NE … NNW. */
    fun cardinal(degrees: Double): String {
        val names = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return names[((((degrees % 360) + 360) % 360) / 22.5).roundToInt() % 16]
    }

    /** "248° WSW" */
    fun bearingLabel(degrees: Double): String = "${degrees.roundToInt() % 360}° ${cardinal(degrees)}"

    /** "34.2°S 81.7°E" */
    fun latLon(latitude: Double, longitude: Double): String {
        val ns = if (latitude < 0) "S" else "N"
        val ew = if (longitude < 0) "W" else "E"
        return String.format(Locale.US, "%.1f°%s %.1f°%s", abs(latitude), ns, abs(longitude), ew)
    }

    /** The smallest signed difference a - b, in degrees (-180..180). */
    fun angleDiff(a: Double, b: Double): Double = ((a - b) % 360 + 540) % 360 - 180
}
