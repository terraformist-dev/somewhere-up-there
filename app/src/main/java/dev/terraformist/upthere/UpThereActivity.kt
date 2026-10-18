package dev.terraformist.upthere

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView

/** The app itself: one screen, with a button for reminders. */
class UpThereActivity : Activity() {

    private lateinit var screen: UpThereView
    private lateinit var controls: View
    private lateinit var button: TextView
    private lateinit var widgetHelp: TextView

    private lateinit var heading: Heading
    private var watcher: LocationListener? = null
    private var askedThisTime = false
    private val main = Handler(Looper.getMainLooper())

    /** Redraw five times a second: the distance and countdown are always moving. */
    private val tick = object : Runnable {
        override fun run() {
            show()
            main.postDelayed(this, 200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        screen = findViewById(R.id.screen)
        controls = findViewById(R.id.controls)
        button = findViewById(R.id.remind)
        widgetHelp = findViewById(R.id.widget_help)

        button.typeface = Art.typeface(this, isBold = true)
        widgetHelp.typeface = Art.typeface(this, isBold = false)

        heading = Heading(this, Facing.SCREEN) {
            screen.heading = it
            screen.invalidate()
        }

        controls.addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ ->
            screen.bottomSpace = (screen.height - top).toFloat().coerceAtLeast(0f)
            screen.invalidate()
        }
        button.setOnClickListener { onButton() }
        widgetHelp.setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), ASK_BACKGROUND)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!Where.hasLocation(this) && !askedThisTime) {
            askedThisTime = true
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                ASK_LOCATION,
            )
        }
        start()
        main.removeCallbacks(tick)
        main.post(tick)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
        Where.stop(this, watcher)
        watcher = null
        heading.stop()
        Reminders.schedule(this)
        // Leave the widget up to date.
        UpThereWidget.refreshAll(this)
    }

    private fun start() {
        if (Where.hasBackground(this)) Where.followInBackground(this)
        OrbitData.refreshIfDue(this) { changed ->
            if (changed) {
                Now.forget()
                Reminders.schedule(this)
            }
            show()
        }
        Where.last(this)?.let { heading.setLocation(it) }
        if (!Where.hasLocation(this)) return
        if (watcher == null) {
            watcher = Where.watch(this, everyMs = 60_000L) { moved(it) }
        }
        Where.refresh(this) { fresh -> if (fresh != null) moved(fresh) }
        heading.start()
    }

    private fun moved(location: Location) {
        heading.setLocation(location)
        show()
    }

    private fun show() {
        val s = Now.situation(this)
        screen.situation = s
        screen.orbitFailed = OrbitData.failed(this)
        screen.contentDescription = Words.describe(s)
        screen.invalidate()

        val overhead = s.stage == Stage.OVERHEAD
        controls.visibility = if (overhead) View.INVISIBLE else View.VISIBLE

        val on = Reminders.isOn(this) && Reminders.canNotify(this)
        val text = when {
            s.stage == Stage.NO_PERMISSION -> "ALLOW LOCATION"
            on -> "REMINDERS ON · TAP TO STOP"
            else -> "REMIND ME 5 MIN BEFORE"
        }
        if (button.text != text) button.text = text
        val dark = on && s.stage != Stage.NO_PERMISSION
        button.setBackgroundResource(if (dark) R.drawable.button_pill_dark else R.drawable.button_pill)
        button.setTextColor(if (dark) Art.WHITE else Art.BLACK)

        val allowed = Where.hasLocation(this)
        widgetHelp.visibility = if (allowed && !Where.hasBackground(this)) View.VISIBLE else View.GONE
    }

    private fun onButton() {
        if (!Where.hasLocation(this)) {
            // They said no before, so Android won't ask again: open this app's settings instead.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        if (Reminders.isOn(this) && Reminders.canNotify(this)) {
            Reminders.setOn(this, false)
        } else if (Reminders.canNotify(this)) {
            Reminders.setOn(this, true)
        } else {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), ASK_NOTIFY)
        }
        show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            ASK_NOTIFY -> if (granted) {
                Reminders.setOn(this, true)
            } else if (!shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                // Refused for good: Android won't ask again, so open the app's notification settings.
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                )
            }
            else -> if (granted) {
                start()
                UpThereWidget.refreshAll(this)
            }
        }
        show()
    }

    private companion object {
        const val ASK_LOCATION = 1
        const val ASK_BACKGROUND = 2
        const val ASK_NOTIFY = 3
    }
}
