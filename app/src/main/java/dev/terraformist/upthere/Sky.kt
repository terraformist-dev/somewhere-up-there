package dev.terraformist.upthere

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Where something is in the sky from where you stand. */
data class Look(
    /** Degrees clockwise from true north. */
    val azimuth: Double,
    /** Degrees above the horizon (negative: below it). */
    val elevation: Double,
    /** Straight-line distance, km. */
    val rangeKm: Double,
)

/** Where the ISS is right now, seen from where you are. */
data class IssNow(
    /** The point on the ground it's directly above. */
    val latitude: Double,
    val longitude: Double,
    /** Distance over the ground from you to that point, metres. */
    val groundMeters: Double,
    /** Compass direction to that point, degrees clockwise from true north. */
    val bearing: Double,
    /** Where it is in your sky (usually below the horizon). */
    val look: Look,
)

/**
 * Turning positions into directions: Earth's rotation, your place on it, and the Sun.
 * Accurate to well under a degree, which is plenty for pointing a phone at the sky.
 */
object Sky {

    private const val DEG = PI / 180.0
    private const val EARTH_EQUATOR_KM = 6378.137
    private const val EARTH_F = 1.0 / 298.257223563
    private const val SHADOW_KM = 6378.0

    fun julian(millis: Long): Double = millis / 86_400_000.0 + 2440587.5

    /** Greenwich sidereal time, radians: how far the Earth has turned. */
    fun gmst(jd: Double): Double {
        val t = (jd - 2451545.0) / 36525.0
        var temp = -6.2e-6 * t * t * t + 0.093104 * t * t + (876600.0 * 3600 + 8640184.812866) * t + 67310.54841
        temp = (temp * DEG / 240.0) % (2 * PI)
        if (temp < 0) temp += 2 * PI
        return temp
    }

    /** TEME position (km) → Earth-fixed position (km), turning with the Earth. */
    fun toEarthFixed(teme: DoubleArray, gmst: Double): DoubleArray {
        val c = cos(gmst)
        val s = sin(gmst)
        return doubleArrayOf(c * teme[0] + s * teme[1], -s * teme[0] + c * teme[1], teme[2])
    }

    /** Your position (km, Earth-fixed), on the WGS-84 ellipsoid. */
    fun observer(latitude: Double, longitude: Double): DoubleArray {
        val lat = latitude * DEG
        val lon = longitude * DEG
        val e2 = EARTH_F * (2 - EARTH_F)
        val n = EARTH_EQUATOR_KM / sqrt(1 - e2 * sin(lat) * sin(lat))
        return doubleArrayOf(n * cos(lat) * cos(lon), n * cos(lat) * sin(lon), n * (1 - e2) * sin(lat))
    }

    /** Direction and distance to an Earth-fixed point [target] from you. */
    fun look(latitude: Double, longitude: Double, observer: DoubleArray, target: DoubleArray): Look {
        val lat = latitude * DEG
        val lon = longitude * DEG
        val rx = target[0] - observer[0]
        val ry = target[1] - observer[1]
        val rz = target[2] - observer[2]
        val south = sin(lat) * cos(lon) * rx + sin(lat) * sin(lon) * ry - cos(lat) * rz
        val east = -sin(lon) * rx + cos(lon) * ry
        val up = cos(lat) * cos(lon) * rx + cos(lat) * sin(lon) * ry + sin(lat) * rz
        val range = sqrt(rx * rx + ry * ry + rz * rz)
        val el = asin((up / range).coerceIn(-1.0, 1.0)) / DEG
        val az = (atan2(east, -south) / DEG + 360.0) % 360.0
        return Look(az, el, range)
    }

    /** The point on the ground under an Earth-fixed position: latitude, longitude in degrees. */
    fun groundPoint(fixed: DoubleArray): Pair<Double, Double> {
        val x = fixed[0]
        val y = fixed[1]
        val z = fixed[2]
        val p = sqrt(x * x + y * y)
        val e2 = EARTH_F * (2 - EARTH_F)
        var lat = atan2(z, p * (1 - e2))
        repeat(4) {
            val n = EARTH_EQUATOR_KM / sqrt(1 - e2 * sin(lat) * sin(lat))
            lat = atan2(z + e2 * n * sin(lat), p)
        }
        return lat / DEG to atan2(y, x) / DEG
    }

    /** Unit vector towards the Sun, in the same frame as TEME (near enough). */
    fun sun(jd: Double): DoubleArray {
        val n = jd - 2451545.0
        val l = (280.460 + 0.9856474 * n) * DEG
        val g = (357.528 + 0.9856003 * n) * DEG
        val lambda = l + (1.915 * sin(g) + 0.020 * sin(2 * g)) * DEG
        val eps = (23.439 - 0.0000004 * n) * DEG
        return doubleArrayOf(cos(lambda), cos(eps) * sin(lambda), sin(eps) * sin(lambda))
    }

    /** The Sun's height above your horizon, degrees. */
    fun sunElevation(jd: Double, latitude: Double, longitude: Double): Double {
        val s = toEarthFixed(sun(jd), gmst(jd))
        val lat = latitude * DEG
        val lon = longitude * DEG
        val up = cos(lat) * cos(lon) * s[0] + cos(lat) * sin(lon) * s[1] + sin(lat) * s[2]
        return asin(up.coerceIn(-1.0, 1.0)) / DEG
    }

    /** True if a TEME position is in sunlight (outside the Earth's shadow). */
    fun sunlit(teme: DoubleArray, sun: DoubleArray): Boolean {
        val along = teme[0] * sun[0] + teme[1] * sun[1] + teme[2] * sun[2]
        if (along > 0) return true
        val px = teme[0] - along * sun[0]
        val py = teme[1] - along * sun[1]
        val pz = teme[2] - along * sun[2]
        return sqrt(px * px + py * py + pz * pz) > SHADOW_KM
    }

    /** Everything about where the ISS is at [millis], from where you are. */
    fun issNow(orbit: Sgp4, latitude: Double, longitude: Double, millis: Long): IssNow {
        val jd = julian(millis)
        val fixed = toEarthFixed(orbit.position(millis), gmst(jd))
        val (lat, lon) = groundPoint(fixed)
        val look = look(latitude, longitude, observer(latitude, longitude), fixed)
        return IssNow(
            latitude = lat,
            longitude = lon,
            groundMeters = Geo.distanceMeters(latitude, longitude, lat, lon),
            bearing = Geo.bearingDegrees(latitude, longitude, lat, lon),
            look = look,
        )
    }
}
