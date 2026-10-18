package dev.terraformist.upthere

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Where the phone is. The last known position is saved, so the widget and Glyph Toy
 * still have something to show when they can't get a fresh one.
 * Passes only change noticeably over kilometres, so a rough position is plenty.
 */
object Where {

    private const val PREFS = "where"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_AT = "at"

    const val ACTION_MOVED = "dev.terraformist.upthere.MOVED"

    private val worker = Executors.newSingleThreadExecutor()

    fun hasLocation(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasPrecise(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackground(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** The last place we know the phone was, or null if never. */
    fun last(context: Context): Location? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LAT)) return null
        return Location("saved").apply {
            latitude = Double.fromBits(prefs.getLong(KEY_LAT, 0))
            longitude = Double.fromBits(prefs.getLong(KEY_LON, 0))
            time = prefs.getLong(KEY_AT, 0)
        }
    }

    fun save(context: Context, location: Location) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAT, location.latitude.toBits())
            .putLong(KEY_LON, location.longitude.toBits())
            .putLong(KEY_AT, location.time.takeIf { it > 0 } ?: System.currentTimeMillis())
            .apply()
    }

    private fun manager(context: Context) = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private fun bestProvider(manager: LocationManager): String? {
        val providers = manager.getProviders(true)
        return when {
            LocationManager.FUSED_PROVIDER in providers -> LocationManager.FUSED_PROVIDER
            LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
            else -> providers.firstOrNull { it != LocationManager.PASSIVE_PROVIDER }
        }
    }

    /**
     * Try for a fresh position, save it, then call [done] on the main thread (with the
     * fresh position, or null if there wasn't one). Works without permission: just returns null.
     */
    fun refresh(context: Context, done: (Location?) -> Unit) {
        val app = context.applicationContext
        val main = android.os.Handler(Looper.getMainLooper())
        val manager = manager(app)
        if (manager == null || !hasLocation(app)) {
            done(null)
            return
        }
        try {
            // A recent fix from any app is good enough and costs nothing.
            val recent = manager.getProviders(true)
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
            if (recent != null && System.currentTimeMillis() - recent.time < 60_000L) {
                save(app, recent)
                done(recent)
                return
            }
            val provider = bestProvider(manager)
            if (provider == null) {
                recent?.let { save(app, it) }
                done(recent)
                return
            }
            manager.getCurrentLocation(provider, null, worker) { fresh ->
                val found = fresh ?: recent
                found?.let { save(app, it) }
                main.post { done(found) }
            }
        } catch (e: SecurityException) {
            done(null)
        }
    }

    /** Follow the phone while something is on screen. Call [stop] with the returned listener. */
    fun watch(context: Context, everyMs: Long, onMove: (Location) -> Unit): LocationListener? {
        val app = context.applicationContext
        val manager = manager(app) ?: return null
        if (!hasLocation(app)) return null
        val provider = bestProvider(manager) ?: return null
        val listener = LocationListener { location ->
            save(app, location)
            onMove(location)
        }
        return try {
            manager.requestLocationUpdates(provider, everyMs, 0f, listener, Looper.getMainLooper())
            listener
        } catch (e: SecurityException) {
            null
        }
    }

    fun stop(context: Context, listener: LocationListener?) {
        if (listener == null) return
        manager(context.applicationContext)?.removeUpdates(listener)
    }

    /**
     * With "Allow all the time", ask Android to tell the widget whenever the phone has moved a
     * couple of kilometres (passes barely change for less), so it stays right without the app
     * being opened. Android decides how often to save battery.
     */
    fun followInBackground(context: Context) {
        val app = context.applicationContext
        val manager = manager(app) ?: return
        if (!hasBackground(app)) return
        val provider = bestProvider(manager) ?: return
        val request = LocationRequest.Builder(15 * 60_000L)
            .setMinUpdateDistanceMeters(2_000f)
            .setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
            .build()
        try {
            manager.requestLocationUpdates(provider, request, movedIntent(app))
        } catch (e: SecurityException) {
            // Permission was taken away; the widget falls back to the last known position.
        }
    }

    private fun movedIntent(context: Context): PendingIntent {
        val intent = Intent(context, UpThereWidget.Small::class.java).setAction(ACTION_MOVED)
        return PendingIntent.getBroadcast(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /** The position delivered with an [ACTION_MOVED] broadcast. */
    @Suppress("DEPRECATION")
    fun fromMovedIntent(intent: Intent): Location? =
        intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED, Location::class.java)
}
