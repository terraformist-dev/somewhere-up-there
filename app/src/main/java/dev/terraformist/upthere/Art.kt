package dev.terraformist.upthere

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything drawn on screen: the dotted compass, the sky dome, the dot-matrix numbers and the
 * small Space Mono labels. Shared by the widget and the app so they always match.
 */
object Art {

    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFF2F2F2.toInt()
    const val GREY = 0xFF9A9A9A.toInt()
    const val FAINT = 0xFF6A6A6A.toInt()
    const val GHOST = 0xFF333333.toInt()
    const val RING = 0xFF3A3A3A.toInt()
    const val ALT_RING = 0xFF2C2C2C.toInt()
    const val TRACK_AHEAD = 0xFF4A4A4A.toInt()
    const val TRACK_DONE = 0xFFB8B8B8.toInt()
    const val RED = 0xFFD71921.toInt()

    private var regular: Typeface? = null
    private var bold: Typeface? = null

    fun typeface(context: Context, isBold: Boolean): Typeface {
        val cached = if (isBold) bold else regular
        if (cached != null) return cached
        val loaded = try {
            Typeface.createFromAsset(context.assets, if (isBold) "SpaceMono-Bold.ttf" else "SpaceMono-Regular.ttf")
        } catch (e: RuntimeException) {
            Typeface.create(Typeface.MONOSPACE, if (isBold) Typeface.BOLD else Typeface.NORMAL)
        }
        if (isBold) bold = loaded else regular = loaded
        return loaded
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---------- The space station marker ----------

    /** The red station: a body with a solar panel either side, three dots each. In 120-unit space. */
    private fun station(canvas: Canvas, x: Float, y: Float, color: Int = RED) {
        dotPaint.color = color
        canvas.drawCircle(x, y, 2.6f, dotPaint)
        for (side in intArrayOf(-1, 1)) for (row in -1..1) {
            canvas.drawCircle(x + side * 5.5f, y + row * 3f, 1.5f, dotPaint)
        }
    }

    // ---------- The compass ----------

    // Designed in a 120-unit box: a ring of 48 dots, a dotted disc, a dotted arrow.
    private val ARROW = arrayOf(
        60 to 20, 50 to 30, 60 to 30, 70 to 30, 40 to 40, 60 to 40, 80 to 40,
        30 to 50, 60 to 50, 90 to 50, 60 to 60, 60 to 70, 60 to 80, 60 to 90, 60 to 100,
    )

    private fun ring(canvas: Canvas) {
        dotPaint.color = RING
        for (i in 0 until 48) {
            val a = Math.PI * 2 * i / 48
            canvas.drawCircle((60 + 54 * cos(a)).toFloat(), (60 + 54 * sin(a)).toFloat(), 1.6f, dotPaint)
        }
    }

    private fun ghostDisc(canvas: Canvas, radius: Float, alpha: Int = 255) {
        dotPaint.color = GHOST
        dotPaint.alpha = alpha
        for (gy in 0..12) for (gx in 0..12) {
            val x = gx * 10f
            val y = gy * 10f
            val dx = x - 60f
            val dy = y - 60f
            if (sqrt(dx * dx + dy * dy) <= radius) canvas.drawCircle(x, y, 2f, dotPaint)
        }
        dotPaint.alpha = 255
    }

    /**
     * @param arrow which way to point, degrees clockwise from the top; null draws no arrow.
     * @param north if set, a small white dot on the ring marks north, at this angle from the top.
     * @param dim fades the arrow (a quiet spell: the next pass is days away).
     */
    fun compass(canvas: Canvas, centerX: Float, centerY: Float, size: Float, arrow: Double?, north: Double? = null, dim: Boolean = false) {
        canvas.save()
        canvas.translate(centerX - size / 2f, centerY - size / 2f)
        canvas.scale(size / 120f, size / 120f)

        ring(canvas)
        if (north != null) {
            val a = Math.toRadians(north)
            dotPaint.color = WHITE
            canvas.drawCircle((60 + 54 * sin(a)).toFloat(), (60 - 54 * cos(a)).toFloat(), 2.4f, dotPaint)
        }
        if (arrow != null) canvas.rotate(arrow.toFloat(), 60f, 60f)
        val alpha = if (dim) 115 else 255
        ghostDisc(canvas, 44f, alpha)
        if (arrow != null) {
            dotPaint.color = WHITE
            dotPaint.alpha = alpha
            for ((x, y) in ARROW) canvas.drawCircle(x.toFloat(), y.toFloat(), 3.4f, dotPaint)
            dotPaint.alpha = 255
            station(canvas, 60f, 6f)
        }
        canvas.restore()
    }

    // ---------- The sky dome ----------

    /** Which compass letters the dome shows. */
    enum class Letters { NONE, NORTH, ALL }

    private const val HORIZON = 47f

    private fun plot(azimuth: Double, elevation: Double, rotation: Double): Pair<Float, Float> {
        val r = (90.0 - elevation.coerceIn(0.0, 90.0)) / 90.0 * HORIZON
        val a = Math.toRadians(azimuth - rotation)
        return (60 + r * sin(a)).toFloat() to (60 - r * cos(a)).toFloat()
    }

    /**
     * The sky seen from above, like a map: the middle is straight up, the ring is the horizon.
     * The pass is a dotted path (bright where it's been, faint where it's going) with the
     * station on it at [now]. [rotation] turns the whole sky (the app turns it as you turn).
     */
    fun dome(
        context: Context,
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        size: Float,
        pass: Pass?,
        now: Long,
        rotation: Double = 0.0,
        letters: Letters = Letters.NONE,
    ) {
        canvas.save()
        canvas.translate(centerX - size / 2f, centerY - size / 2f)
        canvas.scale(size / 120f, size / 120f)

        ring(canvas)
        ghostDisc(canvas, 46f)
        dotPaint.color = ALT_RING
        for (i in 0 until 12) {
            val a = Math.PI * 2 * i / 12
            canvas.drawCircle((60 + 16 * cos(a)).toFloat(), (60 + 16 * sin(a)).toFloat(), 1.1f, dotPaint)
        }

        if (pass != null) {
            path(canvas, pass, now, rotation)
            val here = pass.at(now.coerceIn(pass.start, pass.end))
            val (x, y) = plot(here.azimuth, here.elevation, rotation)
            station(canvas, x, y)
        }

        when (letters) {
            Letters.NONE -> {}
            Letters.NORTH -> {
                val (x, y) = plot(0.0, 90.0 - 44.5 / HORIZON * 90.0, rotation)
                centred(context, canvas, "N", x, y, 7f, FAINT)
            }
            Letters.ALL -> {
                for ((i, name) in listOf("N", "E", "S", "W").withIndex()) {
                    val a = Math.toRadians(i * 90.0 - rotation)
                    val x = (60 + 63 * sin(a)).toFloat()
                    val y = (60 - 63 * cos(a)).toFloat()
                    centred(context, canvas, name, x, y, 6.5f, if (i == 0) WHITE else FAINT)
                }
            }
        }
        canvas.restore()
    }

    /** Dots every 5.5 units along the pass. */
    private fun path(canvas: Canvas, pass: Pass, now: Long, rotation: Double) {
        val points = pass.track.map { plot(it.azimuth, it.elevation, rotation) }
        val spacing = 5.5f
        var carry = 0f
        for (i in 1 until points.size) {
            val (x0, y0) = points[i - 1]
            val (x1, y1) = points[i]
            val len = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
            var d = carry
            while (d <= len) {
                val f = if (len == 0f) 0f else d / len
                val t = pass.track[i - 1].millis + ((pass.track[i].millis - pass.track[i - 1].millis) * f).toLong()
                dotPaint.color = if (t <= now) TRACK_DONE else TRACK_AHEAD
                canvas.drawCircle(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, 1.5f, dotPaint)
                d += spacing
            }
            carry = d - len
        }
    }

    // ---------- Dot-matrix text ----------

    /** Draws [text] in dots, top-left at ([left], [top]), [pitch] pixels between dot centres. Returns its width. */
    fun dots(canvas: Canvas, font: DotFont, text: String, left: Float, top: Float, pitch: Float, color: Int = WHITE): Float {
        dotPaint.color = color
        val r = pitch * 0.42f
        font.forEachDot(text) { x, y ->
            canvas.drawCircle(left + x * pitch + pitch / 2f, top + y * pitch + pitch / 2f, r, dotPaint)
        }
        return font.width(text) * pitch
    }

    fun dotsWidth(font: DotFont, text: String, pitch: Float): Float = font.width(text) * pitch

    // ---------- Plain labels ----------

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = Rect()

    private fun setUpText(context: Context, size: Float, isBold: Boolean, color: Int) {
        textPaint.typeface = typeface(context, isBold)
        textPaint.textSize = size
        textPaint.color = color
        textPaint.letterSpacing = 0.08f
    }

    fun textWidth(context: Context, text: String, size: Float, isBold: Boolean = false): Float {
        setUpText(context, size, isBold, WHITE)
        return textPaint.measureText(text)
    }

    fun capHeight(context: Context, size: Float, isBold: Boolean = false): Float {
        setUpText(context, size, isBold, WHITE)
        textPaint.getTextBounds("H", 0, 1, bounds)
        return bounds.height().toFloat()
    }

    /** Draws [text] with its capital letters' top at [top]. Shrinks it to fit [maxWidth]. Returns the cap height. */
    fun label(
        context: Context,
        canvas: Canvas,
        text: String,
        left: Float,
        top: Float,
        size: Float,
        color: Int = GREY,
        isBold: Boolean = false,
        maxWidth: Float = Float.MAX_VALUE,
        centered: Boolean = false,
        right: Boolean = false,
    ): Float {
        setUpText(context, size, isBold, color)
        var w = textPaint.measureText(text)
        if (w > maxWidth) {
            textPaint.textSize = size * maxWidth / w
            w = maxWidth
        }
        textPaint.getTextBounds("H", 0, 1, bounds)
        val cap = bounds.height().toFloat()
        val x = when {
            centered -> left - w / 2f
            right -> left - w
            else -> left
        }
        canvas.drawText(text, x, top + cap, textPaint)
        return cap
    }

    /** One letter centred on a point, bold. */
    private fun centred(context: Context, canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int) {
        setUpText(context, size, true, color)
        textPaint.letterSpacing = 0f
        textPaint.getTextBounds(text, 0, text.length, bounds)
        canvas.drawText(text, x - textPaint.measureText(text) / 2f, y + bounds.height() / 2f, textPaint)
    }

    /** A small filled white status dot. */
    fun statusDot(canvas: Canvas, centerX: Float, centerY: Float, size: Float) {
        dotPaint.color = WHITE
        canvas.drawCircle(centerX, centerY, size / 2f, dotPaint)
    }

    /** A big dotted number with a small bold unit after it, bottoms lined up. Returns the total width. */
    fun numberWithUnit(context: Context, canvas: Canvas, number: String, unit: String, left: Float, top: Float, pitch: Float, unitSize: Float, gap: Float): Float {
        val numberW = dots(canvas, DotFont.LARGE, number, left, top, pitch)
        if (unit.isEmpty()) return numberW
        val cap = capHeight(context, unitSize, isBold = true)
        label(context, canvas, unit, left + numberW + gap, top + 7 * pitch - cap, unitSize, WHITE, isBold = true)
        return numberW + gap + textWidth(context, unit, unitSize, isBold = true)
    }

    // ---------- The home-screen widget ----------

    /** What the widget says in each stage. */
    private class Card(
        /** Dotted headline. */
        val big: String,
        /** Small bold unit after it ("H", "MIN"), or empty. */
        val unit: String,
        /** Grey line. */
        val line2: String,
        /** White line, or null. */
        val line3: String?,
        /** A white dot before line 3. */
        val dot: Boolean,
        /** The headline is words, drawn smaller (like "LOOK UP"). */
        val words: Boolean = false,
    )

    private fun card(s: Situation): Card {
        val p = s.pass
        return when (s.stage) {
            Stage.NO_PERMISSION -> Card("--", "", "TAP TO START", null, false)
            Stage.LOOKING -> Card("--", "", "FINDING YOU", null, false)
            Stage.NO_ORBIT -> Card("--", "", "NEEDS INTERNET ONCE", "TO GET THE ORBIT", false)
            Stage.NONE -> Card("--", "", "NOTHING VISIBLE", "FOR AT LEAST 10 DAYS", false)
            Stage.WAITING -> Card(
                Words.hoursMinutes(s.untilMs), "H",
                "NEXT PASS · ${Words.whenShort(p!!.start, s.now)}",
                Words.rises(p), true,
            )
            Stage.SOON -> Card(
                Words.minutesUp(s.untilMs).toString(), "MIN",
                Words.rises(p!!),
                "${Words.height(p.maxElevation)} HIGH · ${Words.length(p)} LONG", true,
            )
            Stage.OVERHEAD -> {
                val here = p!!.at(s.now)
                Card(
                    "LOOK UP", "",
                    "${Words.height(here.elevation)} HIGH · ${Geo.cardinal(here.azimuth)}",
                    "GONE BY ${Words.clock(p.end)}", true, words = true,
                )
            }
            Stage.QUIET -> {
                val d = Words.days(s.untilMs)
                Card(
                    d.toString(), if (d == 1) "DAY" else "DAYS",
                    "NEXT PASS · ${Words.whenShort(p!!.start, s.now)}",
                    "NONE VISIBLE TILL THEN", false,
                )
            }
        }
    }

    /** The compass or the sky, whichever this stage shows. North is up: a widget can't follow the phone. */
    private fun picture(context: Context, canvas: Canvas, s: Situation, cx: Float, cy: Float, size: Float, letters: Letters) {
        if (s.stage == Stage.OVERHEAD) {
            dome(context, canvas, cx, cy, size, s.pass, s.now, letters = letters)
        } else {
            val arrow = when (s.stage) {
                Stage.WAITING, Stage.SOON, Stage.QUIET -> s.pass?.startAzimuth
                else -> null
            }
            compass(canvas, cx, cy, size, arrow, north = 0.0, dim = s.stage == Stage.QUIET)
        }
    }

    /**
     * Draws the widget at its real size in pixels. Wide (4×2): the picture on the left, the
     * details on the right. Square (2×2): the picture over the headline.
     */
    fun widget(context: Context, width: Int, height: Int, s: Situation): Bitmap {
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (width >= height * 1.6f) wide(context, canvas, width.toFloat(), height.toFloat(), s)
        else square(context, canvas, width.toFloat(), height.toFloat(), s)
        return bmp
    }

    private fun wide(context: Context, canvas: Canvas, w: Float, h: Float, s: Situation) {
        val u = h / 170f
        val pad = 20f * u
        val size = min(h - 2 * pad, w * 0.4f)
        picture(context, canvas, s, pad + size / 2f, h / 2f, size, Letters.NORTH)

        val c = card(s)
        val left = pad + size + 18f * u
        val room = w - left - pad
        val line2Size = 12f * u
        val line3Size = 11f * u
        val unitSize = 14f * u
        val unitGap = 6f * u

        val pitch = if (c.words) {
            min(4.2f * u, room / DotFont.LARGE.width(c.big))
        } else {
            val unitW = if (c.unit.isEmpty()) 0f else textWidth(context, c.unit, unitSize, isBold = true) + unitGap
            min(6f * u, (room - unitW) / DotFont.LARGE.width(c.big))
        }
        val bigH = 7 * pitch
        val gap1 = (if (c.words) 10f else 12f) * u
        val gap2 = 8f * u
        val block = bigH + gap1 + line2Size + (if (c.line3 != null) gap2 + line3Size else 0f)
        val top = (h - block) / 2f

        numberWithUnit(context, canvas, c.big, c.unit, left, top, pitch, unitSize, unitGap)
        val line2Top = top + bigH + gap1
        label(context, canvas, c.line2, left, line2Top, line2Size, GREY, maxWidth = room)

        val line3 = c.line3 ?: return
        val line3Top = line2Top + line2Size + gap2
        var textLeft = left
        if (c.dot) {
            val dotSize = 6f * u
            statusDot(canvas, left + dotSize / 2f, line3Top + capHeight(context, line3Size) / 2f, dotSize)
            textLeft += dotSize + 8f * u
        }
        label(context, canvas, line3, textLeft, line3Top, line3Size, WHITE, maxWidth = w - pad - textLeft)
    }

    private fun square(context: Context, canvas: Canvas, w: Float, h: Float, s: Situation) {
        val u = min(w, h) / 170f
        val pad = 14f * u
        val size = min(92f * u, h * 0.56f)
        val pictureTop = pad + 2f * u
        picture(context, canvas, s, w / 2f, pictureTop + size / 2f, size, Letters.NONE)

        val areaTop = pictureTop + size
        val areaHeight = h - pad - areaTop
        val room = w - 2 * pad
        val c = card(s)

        when (s.stage) {
            Stage.NO_PERMISSION, Stage.LOOKING, Stage.NO_ORBIT -> {
                val size2 = 11f * u
                label(context, canvas, c.line2, w / 2f, areaTop + (areaHeight - size2 * 0.7f) / 2f, size2, GREY, maxWidth = room, centered = true)
            }
            Stage.OVERHEAD -> {
                val pitch = min(3.2f * u, room / DotFont.LARGE.width(c.big))
                val textW = dotsWidth(DotFont.LARGE, c.big, pitch)
                dots(canvas, DotFont.LARGE, c.big, (w - textW) / 2f, areaTop + (areaHeight - 7 * pitch) / 2f, pitch)
            }
            else -> {
                val unitSize = 12f * u
                val gap = 5f * u
                val unitW = if (c.unit.isEmpty()) 0f else textWidth(context, c.unit, unitSize, isBold = true) + gap
                val pitch = min(4.2f * u, (room - unitW) / DotFont.LARGE.width(c.big))
                val total = dotsWidth(DotFont.LARGE, c.big, pitch) + unitW
                numberWithUnit(context, canvas, c.big, c.unit, (w - total) / 2f, areaTop + (areaHeight - 7 * pitch) / 2f, pitch, unitSize, gap)
            }
        }
    }
}
