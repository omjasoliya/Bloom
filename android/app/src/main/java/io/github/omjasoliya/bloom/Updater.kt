package io.github.omjasoliya.bloom

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A newer Bloom on GitHub. */
class Release(val version: String, val notes: String, val apkUrl: String)

/** Optional update check. Bloom asks once; when allowed, it asks GitHub for the latest
 *  version number at most once a day. Files never leave the phone. */
object Updater {
    private const val LATEST = "https://api.github.com/repos/omjasoliya/Bloom/releases/latest"
    private const val DAY = 24 * 60 * 60 * 1000L
    private const val PREFS = "bloom"

    /** "on", "off", or null while the user hasn't been asked yet. */
    fun choice(context: Context): String? = prefs(context).getString("updates", null)

    fun setChoice(context: Context, on: Boolean) {
        prefs(context).edit().putString("updates", if (on) "on" else "off").putLong("checked", 0).apply()
    }

    fun due(context: Context) =
        choice(context) == "on" && System.currentTimeMillis() - prefs(context).getLong("checked", 0) > DAY

    fun currentVersion(context: Context, testVersion: String? = null): String {
        // Debug builds can pretend to be older, so CI can try the whole update flow.
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && testVersion != null) return testVersion
        return context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    }

    /** The latest release if it's newer than [current]; runs on a background thread. */
    fun check(context: Context, current: String): Release? {
        prefs(context).edit().putLong("checked", System.currentTimeMillis()).apply()
        val json = JSONObject(get(LATEST).bufferedReader().use { it.readText() })
        val version = json.getString("tag_name").removePrefix("v")
        if (!newer(version, current)) return null
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name") == "Bloom.apk" } ?: return null
        val notes = json.optString("body").lineSequence().map { it.trim().trimStart('-', '*', '#', ' ') }
            .firstOrNull { it.isNotEmpty() }.orEmpty()
        return Release(version, notes.take(140), apk.getString("browser_download_url"))
    }

    /** Downloads the APK into the app's cache; [progress] gets 0-100. */
    fun download(context: Context, release: Release, progress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "Bloom-${release.version}.apk")
        val connection = open(release.apkUrl)
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    done += n
                    if (total > 0) progress((done * 100 / total).toInt())
                }
            }
        }
        return file
    }

    /** Hands the APK to Android's installer, which checks it's signed with Bloom's key. */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        @Suppress("DEPRECATION")
        return Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun newer(a: String, b: String): Boolean {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun open(url: String, accept: String? = null): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.setRequestProperty("User-Agent", "Bloom-Android")
        accept?.let { connection.setRequestProperty("Accept", it) }
        if (connection.responseCode !in 200..299) throw IOException("GitHub answered ${connection.responseCode}")
        return connection
    }

    private fun get(url: String) = open(url, "application/vnd.github+json").inputStream

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
