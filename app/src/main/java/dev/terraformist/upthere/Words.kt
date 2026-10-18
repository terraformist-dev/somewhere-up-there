package dev.terraformist.upthere

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Everything written on screen about time and passes, in one place. */
object Words {

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    private val WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.UK)
    private val DATE = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

    private fun zone(): ZoneId = ZoneId.systemDefault()

    /** "19:42" */
    fun clock(millis: Long): String = CLOCK.format(Instant.ofEpochMilli(millis).atZone(zone()))

    /** "19:42" today, "TUE 19:42" this week, "26 OCT 19:42" further off. */
    fun whenShort(millis: Long, now: Long): String = whenLabel(millis, now, todayWord = null)

    /** "TODAY 19:42", "TUE 19:42", "26 OCT 19:42". */
    fun whenLong(millis: Long, now: Long): String = whenLabel(millis, now, todayWord = "TODAY")

    private fun whenLabel(millis: Long, now: Long, todayWord: String?): String {
        val at = Instant.ofEpochMilli(millis).atZone(zone())
        val today = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate()
        val days = at.toLocalDate().toEpochDay() - today.toEpochDay()
        val day = when {
            days == 0L -> todayWord
            days in 1..6 -> WEEKDAY.format(at).uppercase(Locale.UK).take(3)
            else -> DATE.format(at).uppercase(Locale.UK)
        }
        return if (day == null) clock(millis) else "$day ${clock(millis)}"
    }

    /** "2:14" (hours:minutes), for the widget. */
    fun hoursMinutes(ms: Long): String {
        val minutes = (ms.coerceAtLeast(0L) / 60_000L)
        return "${minutes / 60}:${two(minutes % 60)}"
    }

    /** "2:14:08", or "6:42" under an hour. */
    fun countdown(ms: Long): String {
        val s = ms.coerceAtLeast(0L) / 1_000L
        val h = s / 3600
        val m = (s / 60) % 60
        return if (h > 0) "$h:${two(m)}:${two(s % 60)}" else "$m:${two(s % 60)}"
    }

    /** Whole minutes, rounded up: 7 means "within 7 minutes". */
    fun minutesUp(ms: Long): Int = ((ms.coerceAtLeast(0L) + 59_999L) / 60_000L).toInt()

    /** Whole days, at least 1. */
    fun days(ms: Long): Int = (ms / 86_400_000L).toInt().coerceAtLeast(1)

    /** "6 MIN" */
    fun length(pass: Pass): String = "${(pass.durationMs / 60_000.0).roundToInt().coerceAtLeast(1)} MIN"

    /** "62°" */
    fun height(degrees: Double): String = "${degrees.roundToInt()}°"

    private fun two(n: Long): String = if (n < 10) "0$n" else "$n"

    /** Spoken description of the widget, for screen readers. */
    fun describe(s: Situation): String {
        val p = s.pass
        return when (s.stage) {
            Stage.NO_PERMISSION -> "Somewhere Up There. Location is off. Tap to start."
            Stage.LOOKING -> "Somewhere Up There. Finding where you are."
            Stage.NO_ORBIT -> "Somewhere Up There. Needs the internet once to get the space station's orbit."
            Stage.NONE -> "No visible space station passes in the next 10 days."
            Stage.OVERHEAD -> p!!.at(s.now).let {
                "The space station is overhead now, ${it.elevation.roundToInt()} degrees high, " +
                    "${Geo.cardinal(it.azimuth)}. Gone by ${clock(p.end)}."
            }
            else -> "Next space station pass at ${whenLong(p!!.start, s.now).lowercase(Locale.UK)}, " +
                "rising ${Geo.cardinal(p.startAzimuth)}, ${p.maxElevation.roundToInt()} degrees high."
        }
    }
}
