package dev.terraformist.upthere

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The ISS's latest published orbit. Downloaded from CelesTrak at most twice a day (it changes
 * when the station boosts its orbit, so a week-old one drifts by minutes), saved, and used
 * offline in between. Every app build also packs in the orbit as it was on the day of the build,
 * so a fresh install works before it has been online.
 */
object OrbitData {

    private const val PREFS = "orbit"
    private const val KEY_TLE = "tle"
    private const val KEY_FETCHED = "fetched"
    private const val KEY_TRIED = "tried"

    private val SOURCES = listOf(
        "https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE",
        "https://live.ariss.org/iss.txt",
    )

    /** Fetch again once the saved orbit is this old. */
    const val REFRESH_MS = 12 * 3_600_000L

    /** Don't hammer the servers: after a failed try, wait this long. */
    private const val RETRY_MS = 30 * 60_000L

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var cached: Tle? = null

    /** The newest orbit we have (saved download or the one packed with the app), or null. */
    fun current(context: Context): Tle? {
        cached?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_TLE, null)?.let { Tle.parse(it) }
        val packed = try {
            context.assets.open("iss.tle").bufferedReader().use { Tle.parse(it.readText()) }
        } catch (e: java.io.IOException) {
            null
        }
        val best = listOfNotNull(saved, packed).maxByOrNull { it.epochMillis }
        cached = best
        return best
    }

    /** True once we've ever had an orbit. */
    fun available(context: Context): Boolean = current(context) != null

    /** True if a download is due (and we haven't just failed one). */
    fun due(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val tried = prefs.getLong(KEY_TRIED, 0L)
        if (now - tried < RETRY_MS) return false
        val fetched = prefs.getLong(KEY_FETCHED, 0L)
        return now - fetched > REFRESH_MS
    }

    /** True if the last download attempt failed and we have nothing at all. */
    fun failed(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return current(context) == null && prefs.getLong(KEY_TRIED, 0L) > prefs.getLong(KEY_FETCHED, 0L)
    }

    /**
     * Download a fresh orbit if one is due. Calls [done] on the main thread with true if the
     * orbit changed. Safe to call often.
     */
    fun refreshIfDue(context: Context, done: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        if (!due(app)) {
            done(false)
            return
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_TRIED, System.currentTimeMillis()).apply()
        worker.execute {
            val fresh = SOURCES.firstNotNullOfOrNull { download(it) }
            var changed = false
            if (fresh != null) {
                val before = current(app)
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_TLE, fresh.text)
                    .putLong(KEY_FETCHED, System.currentTimeMillis())
                    .apply()
                if (before == null || fresh.epochMillis > before.epochMillis) {
                    cached = fresh
                    changed = true
                }
            }
            main.post { done(changed) }
        }
    }

    /** This blocks: call it off the main thread. Returns a valid ISS orbit or null. */
    private fun download(address: String): Tle? = try {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("User-Agent", "SomewhereUpThere/1.0 (Android)")
        try {
            if (connection.responseCode != 200) {
                null
            } else {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                Tle.parse(text)?.takeIf { it.catalog == Tle.ISS }
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        null
    }
}
