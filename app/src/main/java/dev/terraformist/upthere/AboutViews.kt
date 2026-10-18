package dev.terraformist.upthere

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** A line of dot-matrix text, used for the "Look up." heading on the About card. */
class DotTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var text: String = ""
        set(value) {
            field = value
            contentDescription = value
            requestLayout()
            invalidate()
        }

    /** Pixels between dot centres. */
    var pitch: Float = 2.8f * resources.displayMetrics.density
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (DotFont.LARGE.width(text) * pitch).roundToInt() + paddingLeft + paddingRight
        val h = (DotFont.LARGE.rows(text) * pitch).roundToInt() + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        Art.dots(canvas, DotFont.LARGE, text, paddingLeft.toFloat(), paddingTop.toFloat(), pitch)
    }
}

/** The little dotted pass with the red station at the top, in the corner of the About card. */
class MiniPassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        // Drawn in a 44 × 36 box, like the launcher icon: an arc of dots, the station at its top.
        val s = minOf(width / 44f, height / 36f)
        canvas.save()
        canvas.translate((width - 44 * s) / 2f, (height - 36 * s) / 2f)
        canvas.scale(s, s)
        val cx = 22f
        val cy = 36f
        val r = 26f
        for (i in -4..4) {
            if (i in -1..1) continue
            val a = Math.toRadians(270.0 + i * 13.0)
            paint.color = if (i < 0) Art.WHITE else 0xFF787878.toInt()
            canvas.drawCircle(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat(), 1.9f, paint)
        }
        paint.color = Art.RED
        val top = cy - r
        canvas.drawCircle(cx, top, 2.2f, paint)
        for (side in intArrayOf(-1, 1)) for (row in -1..1) {
            canvas.drawCircle(cx + side * 4.6f, top + row * 2.5f, 1.2f, paint)
        }
        canvas.restore()
    }
}
