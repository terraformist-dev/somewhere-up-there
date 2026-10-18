package dev.terraformist.upthere

import android.content.Context
import android.location.Location
import kotlin.math.abs

/**
 * The four stages the agreed design moves through (plus the cases where we can't say yet):
 * - [WAITING]: next pass within a day. Widget points where it'll rise; app and Glyph point at the ISS now.
 * - [SOON]: within the hour. Everything points where it'll rise.
 * - [OVERHEAD]: during a visible pass. The compass becomes the sky.
 * - [QUIET]: nothing visible for a day or more.
 * - [NONE]: nothing visible in the next 10 days.
 */
enum class Stage { NO_PERMISSION, LOOKING, NO_ORBIT, WAITING, SOON, OVERHEAD, QUIET, NONE }

class Situation(
    val stage: Stage,
    val now: Long,
    /** The pass happening now ([Stage.OVERHEAD]) or the next one. */
    val pass: Pass?,
    /** Visible passes still to come (not including one under way). */
    val upcoming: List<Pass>,
    /** Where the ISS is right now, if we know where you are and have its orbit. */
    val iss: IssNow?,
) {
    /** Milliseconds until [pass] starts (or, overhead, until it ends). */
    val untilMs: Long get() = pass?.let { if (stage == Stage.OVERHEAD) it.end - now else it.start - now } ?: 0L

    /** Fraction of the final hour gone, 0..1 (for the Glyph ring). */
    val hourGone: Double get() = (1.0 - untilMs.toDouble() / SOON_MS).coerceIn(0.0, 1.0)

    companion object {
        const val SOON_MS = 60 * 60_000L
        const val QUIET_MS = 24 * 3_600_000L

        /** Which stage [passes] put us in at [now]. No Android code, so it's easy to check. */
        fun stageFor(now: Long, passes: List<Pass>): Pair<Stage, Pass?> {
            passes.firstOrNull { now in it.start..it.end }?.let { return Stage.OVERHEAD to it }
            val next = passes.firstOrNull { it.start > now } ?: return Stage.NONE to null
            val wait = next.start - now
            return when {
                wait <= SOON_MS -> Stage.SOON
                wait <= QUIET_MS -> Stage.WAITING
                else -> Stage.QUIET
            } to next
        }
    }
}

/** Works out the [Situation] for any screen, keeping the (slightly costly) pass list cached. */
object Now {

    private const val DAYS = 10
    private const val RECOMPUTE_MS = 6 * 3_600_000L

    private var orbit: Sgp4? = null
    private var passes: List<Pass> = emptyList()
    private var passesFor: Triple<Long, Double, Double>? = null
    private var passesAt = 0L

    @Synchronized
    fun orbit(context: Context): Sgp4? {
        val tle = OrbitData.current(context) ?: return null
        val have = orbit
        if (have != null && have.tle.epochMillis == tle.epochMillis) return have
        return Sgp4(tle).also { orbit = it }
    }

    @Synchronized
    fun situation(
        context: Context,
        now: Long = System.currentTimeMillis(),
        location: Location? = Where.last(context),
    ): Situation {
        if (!Where.hasLocation(context)) return Situation(Stage.NO_PERMISSION, now, null, emptyList(), null)
        val place = location ?: return Situation(Stage.LOOKING, now, null, emptyList(), null)
        val sgp = orbit(context) ?: return Situation(Stage.NO_ORBIT, now, null, emptyList(), null)

        val list = passes(sgp, place.latitude, place.longitude, now)
        val (stage, pass) = Situation.stageFor(now, list)
        val upcoming = list.filter { it.start > now }
        val iss = Sky.issNow(sgp, place.latitude, place.longitude, now)
        return Situation(stage, now, pass, upcoming, iss)
    }

    /** Visible passes, recomputed when the orbit changes, you move a few km, or every 6 hours. */
    private fun passes(sgp: Sgp4, latitude: Double, longitude: Double, now: Long): List<Pass> {
        val key = passesFor
        val moved = key == null || abs(key.second - latitude) > 0.03 || abs(key.third - longitude) > 0.05
        val stale = key?.first != sgp.tle.epochMillis || now - passesAt > RECOMPUTE_MS || now < passesAt
        if (moved || stale) {
            passes = Passes.predict(sgp, latitude, longitude, now, DAYS)
            passesFor = Triple(sgp.tle.epochMillis, latitude, longitude)
            passesAt = now
        }
        return passes.filter { it.end >= now }
    }

    /** Forget the cached passes (after a new orbit arrives). */
    @Synchronized
    fun forget() {
        passesFor = null
    }
}
