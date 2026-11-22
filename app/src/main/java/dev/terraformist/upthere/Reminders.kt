package dev.terraformist.upthere

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.Locale
import kotlin.math.roundToInt

/**
 * "Remind me 5 min before": once switched on, one notification per visible pass that changes as
 * it gets closer, then removes itself:
 *  - 5 minutes before: a heads-up saying where it'll rise;
 *  - 2 minutes before: "Look up", alerting again, with a live countdown to when it's gone;
 *  - it disappears by itself the moment the pass ends (or earlier, if swiped away).
 * Both stages use the same notification slot, so they never stack.
 */
object Reminders {

    private const val PREFS = "reminders"
    private const val KEY_ON = "on"
    private const val KEY_LAST = "last_stage"
    private const val CHANNEL = "passes"
    private const val ID = 1
    const val LEAD_MS = 5 * 60_000L
    const val LOOK_UP_MS = 2 * 60_000L

    private enum class Step { HEADS_UP, LOOK_UP }

    fun isOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        if (!on) context.getSystemService(NotificationManager::class.java).cancel(ID)
        schedule(context)
    }

    fun canNotify(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Set (or clear) the alarm for the next stage of the next pass. Safe to call often. */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = alarmIntent(app)
        alarms.cancel(intent)
        if (!isOn(app)) return
        val now = System.currentTimeMillis()
        val at = Now.situation(app, now).upcoming
            .flatMap { listOf(it.start - LEAD_MS, it.start - LOOK_UP_MS) }
            .filter { it > now + 2_000L }
            .minOrNull() ?: return
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        3,
        Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Post whichever stage is due now. Works out the stage from the time, so an alarm that
     * Android delays still shows the right thing (even mid-pass, it shows "Look up").
     */
    fun fire(context: Context) {
        val now = System.currentTimeMillis()
        val s = Now.situation(context, now)
        val pass = (if (s.stage == Stage.OVERHEAD) s.pass else null)
            ?: s.upcoming.firstOrNull { it.start - now in 0..(LEAD_MS + 90_000L) }
        if (pass != null && isOn(context) && canNotify(context)) {
            val step = if (pass.start - now > LOOK_UP_MS + 20_000L) Step.HEADS_UP else Step.LOOK_UP
            val key = "${pass.start}:$step"
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_LAST, null) != key) {
                prefs.edit().putString(KEY_LAST, key).apply()
                post(context, pass, step, now)
            }
        }
        schedule(context)
    }

    private fun post(context: Context, pass: Pass, step: Step, now: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Pass reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Before the ISS comes over, and while it's overhead"
            },
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, UpThereActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val where = if (pass.startsHigh) {
            String.format(Locale.UK, "Appears %s, %d° up", Geo.cardinal(pass.startAzimuth), pass.track.first().elevation.roundToInt())
        } else {
            "Rises ${Geo.cardinal(pass.startAzimuth)}"
        }
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            // Gone by itself once the pass is over.
            .setTimeoutAfter((pass.end - now).coerceAtLeast(1_000L))

        when (step) {
            Step.HEADS_UP -> {
                val minutes = (pass.durationMs / 60_000.0).roundToInt().coerceAtLeast(1)
                builder
                    .setContentTitle("The ISS is coming over")
                    .setContentText(
                        String.format(
                            Locale.UK,
                            "%s at %s · %d° high · %d min long",
                            where,
                            Words.clock(pass.start),
                            pass.maxElevation.roundToInt(),
                            minutes,
                        ),
                    )
            }
            Step.LOOK_UP -> {
                val text = if (now >= pass.start) {
                    val here = pass.at(now)
                    "Overhead now · ${here.elevation.roundToInt()}° high, ${Geo.cardinal(here.azimuth)} · gone by ${Words.clock(pass.end)}"
                } else {
                    String.format(
                        Locale.UK,
                        "%s at %s · up to %d° high · gone by %s",
                        where,
                        Words.clock(pass.start),
                        pass.maxElevation.roundToInt(),
                        Words.clock(pass.end),
                    )
                }
                builder
                    .setContentTitle("Look up")
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    // A live countdown in the corner, to when it's gone.
                    .setWhen(pass.end)
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
            }
        }
        manager.notify(ID, builder.build())
    }
}

/** The reminder alarm going off. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.fire(context)
    }
}

/** After a restart, alarms are forgotten: set them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Reminders.schedule(context)
        UpThereWidget.refreshAll(context)
    }
}
