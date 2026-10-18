package dev.terraformist.upthere

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the 25×25 Glyph Matrix shows, as agreed in the mockup:
 *  - waiting (and quiet spells): a dim block on the edge points at the ISS right now, pulsing slowly;
 *  - final hour: the outer ring fills up clockwise like a clock, and a needle points where it'll rise;
 *  - overhead: the matrix is the sky, the station crossing it with a fading trail;
 *  - long press of the Glyph Button: the time to go, in numbers, for a few seconds.
 */
object MatrixArt {

    private const val C = GlyphShape.CENTER.toDouble()
    private const val TIP_RADIUS = 10.4
    private const val SKY_RADIUS = 11.5

    /** The outer ring of the matrix, in clockwise order starting at the top. */
    private val EDGE: List<Pair<Int, Int>> = run {
        val cells = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until GlyphShape.SIZE) for (x in 0 until GlyphShape.SIZE) {
            if (!GlyphShape.contains(x, y)) continue
            val edge = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).any { (dx, dy) -> !GlyphShape.contains(x + dx, y + dy) }
            if (edge) cells += x to y
        }
        cells.sortedBy { (x, y) -> (Math.toDegrees(atan2(x - C, -(y - C))) + 360.0) % 360.0 }
    }

    /**
     * @param facing which way "up" on the matrix points, degrees from north (null: north is up).
     * @param still true for a frame that will stay on the matrix for a while: no pulsing.
     */
    fun render(s: Situation, facing: Double?, showTime: Boolean, still: Boolean, dim: Boolean): DotGrid {
        val grid = DotGrid(GlyphShape.SIZE, GlyphShape.SIZE)
        val now = s.now
        val up = facing ?: 0.0
        when {
            s.stage == Stage.NO_PERMISSION || s.stage == Stage.LOOKING || s.stage == Stage.NO_ORBIT ->
                grid.set(12, 12, (255 * pulse(now, 2_400L, still)).toInt())
            showTime -> time(grid, s)
            s.stage == Stage.SOON -> finalHour(grid, s, up, still)
            s.stage == Stage.OVERHEAD -> sky(grid, s, up, still)
            else -> s.iss?.let { pointer(grid, it.bearing - up, now, still) }
        }
        if (dim) for (i in grid.levels.indices) grid.levels[i] = grid.levels[i] / 2
        return grid
    }

    private fun time(grid: DotGrid, s: Situation) {
        val text = when (s.stage) {
            Stage.WAITING -> Words.hoursMinutes(s.untilMs)
            Stage.SOON, Stage.OVERHEAD -> Words.countdown(s.untilMs)
            Stage.QUIET -> "${Words.days(s.untilMs)}D"
            else -> "--"
        }
        val font = DotFont.SMALL
        grid.text(font, text, (GlyphShape.SIZE - font.width(text)) / 2, (GlyphShape.SIZE - font.height) / 2)
    }

    /** A block of dots at the edge in direction [angle] (degrees clockwise from the top), slow pulse. */
    private fun pointer(grid: DotGrid, angle: Double, now: Long, still: Boolean) {
        val a = Math.toRadians(angle)
        val tipX = C + TIP_RADIUS * sin(a)
        val tipY = C - TIP_RADIUS * cos(a)
        val level = (160 * pulse(now, 3_000L, still)).toInt()
        for (y in 0 until GlyphShape.SIZE) for (x in 0 until GlyphShape.SIZE) {
            val tx = x - tipX
            val ty = y - tipY
            if (sqrt(tx * tx + ty * ty) <= 1.3) grid.lift(x, y, level)
        }
        grid.lift(12, 12, 100)
    }

    private fun finalHour(grid: DotGrid, s: Situation, up: Double, still: Boolean) {
        val pass = s.pass ?: return
        // The ring: lit dots for the part of the hour that's gone.
        val lit = (s.hourGone * EDGE.size).roundToInt()
        for ((i, cell) in EDGE.withIndex()) grid.set(cell.first, cell.second, if (i < lit) 210 else 30)

        // The needle, towards where it'll rise.
        val a = Math.toRadians(pass.startAzimuth - up)
        val dirX = sin(a)
        val dirY = -cos(a)
        for (y in 0 until GlyphShape.SIZE) for (x in 0 until GlyphShape.SIZE) {
            val dx = x - C
            val dy = y - C
            val along = dx * dirX + dy * dirY
            val across = dx * dirY - dy * dirX
            if (along in 1.5..7.6 && abs(across) <= 0.6) grid.lift(x, y, (70 + 130 * along / 7.6).toInt())
        }
        val tx = (C + 8.8 * dirX).roundToInt()
        val ty = (C + 8.8 * dirY).roundToInt()
        grid.lift(tx, ty, (255 * pulse(s.now, 700L, still)).toInt())
        grid.lift(12, 12, 255)
    }

    private fun skyCell(azimuth: Double, elevation: Double, up: Double): Pair<Int, Int> {
        val r = (90.0 - elevation.coerceIn(0.0, 90.0)) / 90.0 * SKY_RADIUS
        val a = Math.toRadians(azimuth - up)
        return (C + r * sin(a)).roundToInt() to (C - r * cos(a)).roundToInt()
    }

    private fun sky(grid: DotGrid, s: Situation, up: Double, still: Boolean) {
        val pass = s.pass ?: return
        val now = s.now
        val gone = (now - pass.start).coerceAtLeast(1L).toDouble()
        // Where it's going: very faint. Where it's been: brighter the more recent.
        for (p in pass.track) {
            val (x, y) = skyCell(p.azimuth, p.elevation, up)
            if (!GlyphShape.contains(x, y)) continue
            if (p.millis > now) {
                grid.lift(x, y, 24)
            } else {
                val fresh = 1.0 - (now - p.millis) / gone
                grid.lift(x, y, (40 + 140 * fresh * fresh).toInt())
            }
        }
        val here = pass.at(now)
        val (x, y) = skyCell(here.azimuth, here.elevation, up)
        grid.set(x, y, (255 * pulse(now, 800L, still)).toInt())
    }

    // ---------- Switch-on ----------

    /** How long the switch-on lasts. */
    const val INTRO_MS = 1_200L
    private const val PASS_MS = 850L
    private const val HORIZON_ROW = 19
    private const val ARC_RADIUS = 10.0

    /**
     * The switch-on, played once when this toy is picked, so it's easy to tell apart from the
     * other Somewhere toys (Nothing's dissolve-and-shoot, Someone's heartbeat): a faint horizon
     * appears and a bright dot rises over it, arcs across the sky with a fading trail and sets
     * on the other side, like a pass. Then the trail fades out as the normal picture ([after])
     * fades in.
     */
    fun intro(elapsed: Long, after: DotGrid): DotGrid {
        val grid = DotGrid(GlyphShape.SIZE, GlyphShape.SIZE)
        val t = elapsed.coerceAtLeast(0L)
        // After the pass, everything from the intro fades while the real picture comes up.
        val fade = ((t - PASS_MS).toDouble() / (INTRO_MS - PASS_MS)).coerceIn(0.0, 1.0)
        val keep = 1.0 - fade

        // The horizon: a faint line of dots.
        for (x in 0 until GlyphShape.SIZE) {
            if (GlyphShape.contains(x, HORIZON_ROW)) grid.lift(x, HORIZON_ROW, (45 * keep).toInt())
        }

        // The dot and its trail, from the left end of the horizon, over the top, to the right end.
        val p = smooth(t.toDouble() / PASS_MS)
        for (back in 0..9) {
            val q = p - back * 0.035
            if (q < 0) break
            val (x, y) = arcCell(q)
            val level = if (back == 0) 255 else (190 - back * 18)
            grid.lift(x, y, (level * keep).toInt())
        }

        if (fade > 0) {
            for (k in after.levels.indices) {
                grid.lift(k % GlyphShape.SIZE, k / GlyphShape.SIZE, (after.levels[k] * fade).toInt())
            }
        }
        return grid
    }

    /** A point [q] (0..1) along the pass arc: a half circle standing on the horizon. */
    private fun arcCell(q: Double): Pair<Int, Int> {
        val a = PI * q.coerceIn(0.0, 1.0)
        return (C - ARC_RADIUS * cos(a)).roundToInt() to (HORIZON_ROW - ARC_RADIUS * sin(a)).roundToInt()
    }

    private fun smooth(t: Double): Double {
        val c = t.coerceIn(0.0, 1.0)
        return c * c * (3 - 2 * c)
    }

    /** 0.3..1, smoothly, once per [period]. Always 1 for a still frame. */
    private fun pulse(now: Long, period: Long, still: Boolean): Double {
        if (still) return 1.0
        val phase = (now % period).toDouble() / period
        return 0.3 + 0.7 * (0.5 + 0.5 * cos(2 * PI * phase))
    }
}
