package dev.terraformist.upthere

import kotlin.math.max

/** One moment of a pass: where to look. */
data class TrackPoint(val millis: Long, val azimuth: Double, val elevation: Double)

/**
 * A visible pass: the stretch of time when the ISS is high enough to see (10° or more), lit by
 * the Sun, and you're in darkness (the Sun at least 6° below your horizon).
 */
data class Pass(
    val start: Long,
    val end: Long,
    val maxElevation: Double,
    /** Points every few seconds from [start] to [end]. */
    val track: List<TrackPoint>,
) {
    val startAzimuth: Double get() = track.first().azimuth
    val endAzimuth: Double get() = track.last().azimuth
    val durationMs: Long get() = end - start

    /** Where it is at [millis] during the pass, smoothly between track points. */
    fun at(millis: Long): TrackPoint {
        if (millis <= track.first().millis) return track.first()
        if (millis >= track.last().millis) return track.last()
        var i = 1
        while (i < track.size && track[i].millis < millis) i++
        val a = track[i - 1]
        val b = track[i]
        val f = (millis - a.millis).toDouble() / (b.millis - a.millis).coerceAtLeast(1L)
        val dAz = Geo.angleDiff(b.azimuth, a.azimuth)
        return TrackPoint(
            millis,
            (a.azimuth + dAz * f + 360.0) % 360.0,
            a.elevation + (b.elevation - a.elevation) * f,
        )
    }
}

object Passes {

    const val MIN_ELEVATION = 10.0
    const val MAX_SUN = -6.0
    const val MIN_LENGTH_MS = 30_000L

    private const val COARSE_MS = 60_000L
    private const val FINE_MS = 5_000L

    /**
     * Every visible pass from [from] (including one already under way) for the next [days] days.
     */
    fun predict(orbit: Sgp4, latitude: Double, longitude: Double, from: Long, days: Int = 10): List<Pass> {
        val observer = Sky.observer(latitude, longitude)
        val begin = from - 20 * 60_000L
        val until = from + days * 86_400_000L

        fun elevation(t: Long): Double {
            val jd = Sky.julian(t)
            val fixed = Sky.toEarthFixed(orbit.position(t), Sky.gmst(jd))
            return Sky.look(latitude, longitude, observer, fixed).elevation
        }

        val passes = mutableListOf<Pass>()
        var t = begin
        var spanStart = -1L
        var spanMax = -90.0
        while (t <= until) {
            val el = elevation(t)
            if (el > 0) {
                if (spanStart < 0) {
                    spanStart = t
                    spanMax = el
                } else {
                    spanMax = max(spanMax, el)
                }
            } else if (spanStart >= 0) {
                // The ISS was above the horizon from about spanStart to t. Look closer if it got high enough.
                if (spanMax >= MIN_ELEVATION - 3) {
                    visibleRuns(orbit, latitude, longitude, observer, spanStart - COARSE_MS, t)
                        .filter { it.end >= from }
                        .forEach { passes += it }
                }
                spanStart = -1L
            }
            t += COARSE_MS
        }
        return passes
    }

    private fun visibleRuns(
        orbit: Sgp4,
        latitude: Double,
        longitude: Double,
        observer: DoubleArray,
        from: Long,
        to: Long,
    ): List<Pass> {
        val runs = mutableListOf<Pass>()
        var points = mutableListOf<TrackPoint>()
        var top = -90.0

        fun close() {
            if (points.size >= 2) {
                val start = points.first().millis
                val end = points.last().millis
                if (end - start >= MIN_LENGTH_MS) runs += Pass(start, end, top, points)
            }
            points = mutableListOf()
            top = -90.0
        }

        var t = from
        while (t <= to) {
            val jd = Sky.julian(t)
            val teme = orbit.position(t)
            val fixed = Sky.toEarthFixed(teme, Sky.gmst(jd))
            val look = Sky.look(latitude, longitude, observer, fixed)
            val visible = look.elevation >= MIN_ELEVATION &&
                Sky.sunElevation(jd, latitude, longitude) <= MAX_SUN &&
                Sky.sunlit(teme, Sky.sun(jd))
            if (visible) {
                points += TrackPoint(t, look.azimuth, look.elevation)
                top = max(top, look.elevation)
            } else {
                close()
            }
            t += FINE_MS
        }
        close()
        return runs
    }
}
