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
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/** The app itself: one screen, with a button for reminders. */
class UpThereActivity : Activity() {

    private lateinit var screen: UpThereView
    private lateinit var controls: View
    private lateinit var button: TextView

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

        button.typeface = Art.typeface(this, isBold = true)

        heading = Heading(this, Facing.SCREEN) {
            screen.heading = it
            screen.invalidate()
        }

        controls.addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ ->
            screen.bottomSpace = (screen.height - top).toFloat().coerceAtLeast(0f)
            screen.invalidate()
        }
        button.setOnClickListener { onButton() }
        setUpAbout()
    }

    // ---------- About card (tap the title) ----------

    private lateinit var aboutScrim: View
    private val closeAboutOnBack = OnBackInvokedCallback { hideAbout() }

    private fun setUpAbout() {
        aboutScrim = findViewById(R.id.about_scrim)
        val titleTap = findViewById<View>(R.id.title_tap)

        val regular = Art.typeface(this, isBold = false)
        for (id in intArrayOf(R.id.about_line1, R.id.about_line2, R.id.about_tips, R.id.about_signoff)) {
            findViewById<TextView>(id).typeface = regular
        }
        findViewById<TextView>(R.id.about_ok).typeface = Art.typeface(this, isBold = true)
        findViewById<DotTextView>(R.id.about_headline).text = getString(R.string.about_headline)

        // Keep the invisible tap area sitting exactly on the drawn title, whatever the screen size.
        screen.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val minHeight = 48f * resources.displayMetrics.density
            val width = (screen.titleWidth() + 32f * resources.displayMetrics.density).toInt()
            val params = titleTap.layoutParams as FrameLayout.LayoutParams
            val top = (screen.titleCenterY() - minHeight / 2f).toInt().coerceAtLeast(0)
            if (params.width != width || params.topMargin != top) {
                params.width = width
                params.height = minHeight.toInt()
                params.topMargin = top
                // Not during this layout pass: on the next frame.
                titleTap.post { titleTap.layoutParams = params }
            }
        }

        titleTap.setOnClickListener { showAbout() }
        aboutScrim.setOnClickListener { hideAbout() }
        findViewById<View>(R.id.about_ok).setOnClickListener { hideAbout() }
    }

    private fun showAbout() {
        if (aboutScrim.visibility == View.VISIBLE) return
        aboutScrim.alpha = 0f
        aboutScrim.visibility = View.VISIBLE
        aboutScrim.animate().alpha(1f).setDuration(160L).start()
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            closeAboutOnBack,
        )
        findViewById<View>(R.id.about_headline).sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    private fun hideAbout() {
        if (aboutScrim.visibility != View.VISIBLE) return
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(closeAboutOnBack)
        aboutScrim.animate().alpha(0f).setDuration(120L).withEndAction {
            aboutScrim.visibility = View.GONE
        }.start()
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
        const val ASK_NOTIFY = 3
    }
}
