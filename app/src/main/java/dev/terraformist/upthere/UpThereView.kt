package dev.terraformist.upthere

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * The app's one screen, drawn by hand so it matches the widget dot for dot.
 * - Waiting: a live compass to the ISS wherever it is, its distance, the countdown, the next passes.
 * - Final hour: the compass points where it'll rise.
 * - Overhead: the sky, turning as you turn, with the station crossing it.
 * Unlike the widget, everything turns with the phone.
 */
class UpThereView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var situation: Situation? = null

    /** Which way the top of the screen faces (degrees from north), or null if unknown. */
    var heading: Double? = null

    /** True if we've never had the orbit and the download failed. */
    var orbitFailed = false

    /** Pixels at the bottom covered by the buttons. */
    var bottomSpace = 0f

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Art.BLACK)
        val s = situation ?: return
        val w = width.toFloat()
        val u = w / 390f
        val cx = w / 2f
        val room = w - 48f * u

        var y = 40f * u
        Art.label(context, canvas, TITLE, cx, y, 11f * u, LABEL_GREY, centered = true)
        y += 11f * u

        when (s.stage) {
            Stage.OVERHEAD -> overhead(canvas, s, u, cx, y + 32f * u, room)
            Stage.NO_PERMISSION, Stage.LOOKING, Stage.NO_ORBIT -> notYet(canvas, s, u, cx, y + 32f * u)
            else -> waiting(canvas, s, u, cx, y + 32f * u, room)
        }
    }

    private fun notYet(canvas: Canvas, s: Situation, u: Float, cx: Float, top: Float) {
        var y = top
        val size = min(240f * u, height - y - bottomSpace - 200f * u).coerceAtLeast(120f * u)
        Art.compass(canvas, cx, y + size / 2f, size, arrow = null, north = heading?.let { -it })
        y += size + 28f * u
        val pitch = 11f * u
        Art.dots(canvas, DotFont.LARGE, "--", cx - Art.dotsWidth(DotFont.LARGE, "--", pitch) / 2f, y, pitch)
        y += 7 * pitch + 18f * u
        val words = when (s.stage) {
            Stage.NO_PERMISSION -> "LOCATION IS OFF"
            Stage.LOOKING -> "FINDING YOU"
            else -> if (orbitFailed) "NEEDS THE INTERNET ONCE" else "GETTING THE ORBIT"
        }
        Art.label(context, canvas, words, cx, y, 13f * u, Art.GREY, centered = true)
    }

    private fun waiting(canvas: Canvas, s: Situation, u: Float, cx: Float, top: Float, room: Float) {
        val h = heading
        val iss = s.iss
        val soon = s.stage == Stage.SOON
        val pass = s.pass

        // Everything under the compass, measured first so the compass can take what's left.
        val bigPitch = 11f * u
        val clockPitch = 7f * u
        val rows = min(3, s.upcoming.size)
        val below = 22f * u + 7 * bigPitch + 16f * u + 12f * u + 26f * u + 22f * u + 11f * u + 12f * u +
            7 * clockPitch + 24f * u + 10f * u + max(1, rows) * 22f * u
        val size = min(240f * u, height - top - below - bottomSpace - 16f * u).coerceAtLeast(110f * u)

        var y = top
        val target = if (soon) pass?.startAzimuth else iss?.bearing
        val arrow = target?.let { if (h != null) it - h else it }
        Art.compass(canvas, cx, y + size / 2f, size, arrow, north = h?.let { -it } ?: 0.0)
        y += size + 22f * u

        // Headline: how far away the ISS is now, or (in the final hour) the direction it'll rise.
        if (soon && pass != null) {
            val word = Geo.cardinal(pass.startAzimuth)
            val pitch = min(bigPitch, room / DotFont.LARGE.width(word))
            Art.dots(canvas, DotFont.LARGE, word, cx - Art.dotsWidth(DotFont.LARGE, word, pitch) / 2f, y, pitch)
            y += 7 * bigPitch + 16f * u
            twoTone(canvas, "WHERE IT RISES", "${Math.round(pass.startAzimuth) % 360}°", cx, y, 12f * u, room)
        } else if (iss != null) {
            val d = Geo.format(iss.groundMeters)
            val unitSize = 18f * u
            val gap = 10f * u
            val unitW = Art.textWidth(context, d.unit, unitSize, isBold = true)
            val pitch = min(bigPitch, (room - unitW - gap) / DotFont.LARGE.width(d.number))
            val total = Art.dotsWidth(DotFont.LARGE, d.number, pitch) + gap + unitW
            Art.numberWithUnit(context, canvas, d.number, d.unit, cx - total / 2f, y + 7 * (bigPitch - pitch), pitch, unitSize, gap)
            y += 7 * bigPitch + 16f * u
            twoTone(canvas, Geo.latLon(iss.latitude, iss.longitude), Geo.bearingLabel(iss.bearing), cx, y, 12f * u, room)
        }
        y += 12f * u + 26f * u

        // A dotted divider.
        dottedLine(canvas, cx - room / 2f, cx + room / 2f, y, u)
        y += 22f * u

        // The countdown.
        val label = when (s.stage) {
            Stage.SOON -> "RISES IN"
            Stage.NONE -> "NEXT PASS"
            else -> "NEXT PASS IN"
        }
        Art.label(context, canvas, label, cx, y, 11f * u, LABEL_GREY, centered = true)
        y += 11f * u + 12f * u
        when (s.stage) {
            Stage.QUIET -> {
                val n = Words.days(s.untilMs)
                val unit = if (n == 1) "DAY" else "DAYS"
                val unitSize = 14f * u
                val gap = 8f * u
                val total = Art.dotsWidth(DotFont.LARGE, n.toString(), clockPitch) + gap + Art.textWidth(context, unit, unitSize, isBold = true)
                Art.numberWithUnit(context, canvas, n.toString(), unit, cx - total / 2f, y, clockPitch, unitSize, gap)
            }
            Stage.NONE -> {
                Art.label(context, canvas, "NONE VISIBLE IN THE NEXT 10 DAYS", cx, y + 7 * clockPitch / 2f - 6f * u, 12f * u, Art.WHITE, maxWidth = room, centered = true)
            }
            else -> {
                val text = Words.countdown(s.untilMs)
                Art.dots(canvas, DotFont.LARGE, text, cx - Art.dotsWidth(DotFont.LARGE, text, clockPitch) / 2f, y, clockPitch)
            }
        }
        y += 7 * clockPitch + 24f * u

        // The next few passes.
        if (rows == 0) return
        val left = cx - room / 2f
        val right = cx + room / 2f
        val head = 10f * u
        val grey = LABEL_GREY
        Art.label(context, canvas, "WHEN", left, y, head, grey)
        Art.label(context, canvas, "HOW LONG", cx, y, head, grey, centered = true)
        Art.label(context, canvas, "HOW HIGH", right, y, head, grey, right = true)
        y += head + 12f * u
        for (i in 0 until rows) {
            val p = s.upcoming[i]
            val color = if (i == 0) Art.WHITE else Art.GREY
            val size12 = 12f * u
            Art.label(context, canvas, Words.whenLong(p.start, s.now), left, y, size12, color, maxWidth = room / 3f + 20f * u)
            Art.label(context, canvas, Words.length(p), cx, y, size12, color, centered = true)
            Art.label(context, canvas, Words.height(p.maxElevation), right, y, size12, color, right = true)
            y += 22f * u
        }
    }

    private fun overhead(canvas: Canvas, s: Situation, u: Float, cx: Float, top: Float, room: Float) {
        val pass = s.pass ?: return
        val here = pass.at(s.now)
        val pitch = min(9f * u, room / DotFont.LARGE.width("LOOK UP"))
        val below = 36f * u + 7 * pitch + 20f * u + 13f * u + 14f * u + 12f * u + 90f * u
        // The letters N E S W sit just outside the ring, so leave them a little room.
        val size = min(300f * u, height - top - below - 16f * u).coerceAtLeast(150f * u)
        var y = top + size * 0.06f

        Art.dome(context, canvas, cx, y + size / 2f, size, pass, s.now, rotation = heading ?: 0.0, letters = Art.Letters.ALL)
        y += size + 36f * u

        Art.dots(canvas, DotFont.LARGE, "LOOK UP", cx - Art.dotsWidth(DotFont.LARGE, "LOOK UP", pitch) / 2f, y, pitch)
        y += 7 * pitch + 20f * u

        val range = s.iss?.look?.rangeKm ?: 0.0
        val line = "${Words.height(here.elevation)} HIGH  /  ${Geo.cardinal(here.azimuth)}  /  ${Geo.format(range * 1000).number} KM AWAY"
        Art.label(context, canvas, line, cx, y, 13f * u, Art.WHITE, maxWidth = room, centered = true)
        y += 13f * u + 14f * u

        val gone = "GONE IN ${Words.countdown(s.untilMs)}"
        val goneW = Art.textWidth(context, gone, 12f * u)
        val dotSize = 6f * u
        val startX = cx - (goneW + dotSize + 10f * u) / 2f
        Art.statusDot(canvas, startX + dotSize / 2f, y + Art.capHeight(context, 12f * u) / 2f, dotSize)
        Art.label(context, canvas, gone, startX + dotSize + 10f * u, y, 12f * u, Art.GREY)

        val hintSize = 11f * u
        val bottom = height - 32f * u
        Art.label(context, canvas, "HOLD THE PHONE FLAT", cx, bottom - hintSize, hintSize, Art.FAINT, centered = true)
        Art.label(context, canvas, "THE SKY TURNS AS YOU TURN", cx, bottom - hintSize * 2 - 8f * u, hintSize, Art.FAINT, centered = true)
    }

    /** "grey part  /  white part", centred. */
    private fun twoTone(canvas: Canvas, first: String, second: String, cx: Float, top: Float, size: Float, room: Float) {
        val slash = "  /  "
        val w1 = Art.textWidth(context, first, size)
        val w2 = Art.textWidth(context, slash, size)
        val w3 = Art.textWidth(context, second, size)
        val scale = min(1f, room / (w1 + w2 + w3))
        val s = size * scale
        var x = cx - (w1 + w2 + w3) * scale / 2f
        Art.label(context, canvas, first, x, top, s, Art.GREY)
        x += w1 * scale
        Art.label(context, canvas, slash, x, top, s, 0xFF5A5A5A.toInt())
        x += w2 * scale
        Art.label(context, canvas, second, x, top, s, Art.WHITE)
    }

    private val linePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E2E2E.toInt() }

    private fun dottedLine(canvas: Canvas, from: Float, to: Float, y: Float, u: Float) {
        var x = from
        while (x <= to) {
            canvas.drawCircle(x, y, 1.1f * u, linePaint)
            x += 6f * u
        }
    }

    private companion object {
        const val TITLE = "SOMEWHERE UP THERE"
        const val LABEL_GREY = 0xFF8A8A8A.toInt()
    }
}
