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
 * "Remind me 5 min before": once switched on, a notification 5 minutes before every visible
 * pass, saying where to look. Off until the owner taps the button.
 */
object Reminders {

    private const val PREFS = "reminders"
    private const val KEY_ON = "on"
    private const val KEY_LAST = "last"
    private const val CHANNEL = "passes"
    const val LEAD_MS = 5 * 60_000L

    fun isOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        schedule(context)
    }

    fun canNotify(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Set (or clear) the alarm for the next reminder. Safe to call often. */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = alarmIntent(app)
        alarms.cancel(intent)
        if (!isOn(app)) return
        val now = System.currentTimeMillis()
        val next = Now.situation(app, now).upcoming.firstOrNull { it.start - LEAD_MS > now + 5_000L } ?: return
        val at = next.start - LEAD_MS
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

    /** Post the notification for the pass about to start, if there is one. */
    fun fire(context: Context) {
        val now = System.currentTimeMillis()
        val pass = Now.situation(context, now).upcoming.firstOrNull { it.start - now in 0..(LEAD_MS + 90_000L) }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (pass != null && isOn(context) && canNotify(context) && prefs.getLong(KEY_LAST, 0L) != pass.start) {
            prefs.edit().putLong(KEY_LAST, pass.start).apply()
            post(context, pass)
        }
        schedule(context)
    }

    private fun post(context: Context, pass: Pass) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Pass reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Five minutes before the ISS comes over"
            },
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, UpThereActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val minutes = (pass.durationMs / 60_000.0).roundToInt().coerceAtLeast(1)
        val text = String.format(
            Locale.UK,
            "Rises %s at %s · %d° high · %d min long",
            Geo.cardinal(pass.startAzimuth),
            Words.clock(pass.start),
            pass.maxElevation.roundToInt(),
            minutes,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("The ISS is coming over")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(pass.end - System.currentTimeMillis())
            .build()
        manager.notify(1, notification)
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
