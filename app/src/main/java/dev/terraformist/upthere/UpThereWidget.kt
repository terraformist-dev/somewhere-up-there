package dev.terraformist.upthere

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The home-screen widget, in two sizes ([Small] 2×2 and [Wide] 4×2) that share everything.
 *
 * The countdown has to move, so the widget sets itself a timer for its next redraw: every minute
 * while a pass is less than a day away, every 15 seconds during a pass, every half hour otherwise.
 * The timer doesn't wake the phone: if the screen is off it fires as soon as the phone wakes,
 * which is exactly when the widget can be seen again.
 */
open class UpThereWidget : AppWidgetProvider() {

    class Small : UpThereWidget()
    class Wide : UpThereWidget()

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refreshAll(context)
        Where.followInBackground(context)
        Reminders.schedule(context)
        val pending = goAsync()
        Where.refresh(context) {
            OrbitData.refreshIfDue(context) { changed ->
                if (changed) {
                    Now.forget()
                    Reminders.schedule(context)
                }
                refreshAll(context)
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        draw(context, manager, id, Now.situation(context))
    }

    override fun onDisabled(context: Context) {
        alarms(context).cancel(tickIntent(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Where.ACTION_MOVED -> {
                Where.fromMovedIntent(intent)?.let { Where.save(context, it) }
                refreshAll(context)
            }
            ACTION_TICK -> refreshAll(context)
            else -> super.onReceive(context, intent)
        }
    }

    companion object {

        private const val ACTION_TICK = "dev.terraformist.upthere.TICK"
        private const val MAX_SIDE_PX = 900f

        private fun ids(context: Context): List<Int> {
            val manager = AppWidgetManager.getInstance(context)
            return listOf(Small::class.java, Wide::class.java).flatMap {
                manager.getAppWidgetIds(ComponentName(context, it)).toList()
            }
        }

        /** Redraw every copy of both widgets, then set the timer for the next redraw. */
        fun refreshAll(context: Context) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            val manager = AppWidgetManager.getInstance(context)
            val s = Now.situation(context)
            for (id in ids) draw(context, manager, id, s)
            scheduleNext(context, s)
        }

        private fun draw(context: Context, manager: AppWidgetManager, id: Int, s: Situation) {
            val (widthDp, heightDp) = sizeDp(manager.getAppWidgetOptions(id))
            val density = context.resources.displayMetrics.density
            val scale = minOf(density, MAX_SIDE_PX / max(widthDp, heightDp))
            val picture = Art.widget(context, (widthDp * scale).roundToInt(), (heightDp * scale).roundToInt(), s)

            val views = RemoteViews(context.packageName, R.layout.widget)
            views.setImageViewBitmap(R.id.art, picture)
            views.setContentDescription(R.id.art, Words.describe(s))
            views.setOnClickPendingIntent(R.id.art, openApp(context))
            manager.updateAppWidget(id, views)
        }

        private fun scheduleNext(context: Context, s: Situation) {
            val now = s.now
            val next = when (s.stage) {
                Stage.OVERHEAD -> now + 15_000L
                Stage.WAITING, Stage.SOON -> (now / 60_000L + 1) * 60_000L + 500L
                Stage.QUIET -> minOf(now + 30 * 60_000L, s.pass!!.start - Situation.QUIET_MS + 1_000L)
                else -> now + 30 * 60_000L
            }
            val alarms = alarms(context)
            val intent = tickIntent(context)
            // RTC (not RTC_WAKEUP): never wakes the phone just to redraw.
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExact(AlarmManager.RTC, next, intent)
            } else {
                alarms.set(AlarmManager.RTC, next, intent)
            }
        }

        private fun alarms(context: Context) = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            2,
            Intent(context, Small::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** The widget's size on screen, in dp, as the home screen reports it (portrait). */
        private fun sizeDp(options: Bundle): Pair<Float, Float> {
            val sizes = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            val first = sizes?.firstOrNull()
            if (first != null && first.width > 0 && first.height > 0) return first.width to first.height
            val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 170)
            val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 170)
            return w.coerceAtLeast(40).toFloat() to h.coerceAtLeast(40).toFloat()
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, UpThereActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
