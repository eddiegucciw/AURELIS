package com.aurelis.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** In-app updates: finds the newest build on GitHub and installs it over this one. */
object Updater {
    private const val LATEST = "https://api.github.com/repos/eddiegucciw/AURELIS/releases/latest"

    /** Download link of a newer build, or null. */
    val available = MutableStateFlow<String?>(null)
    val progress = MutableStateFlow(-1)
    @Volatile private var checking = false

    fun check() {
        if (checking || !AppState.online.value) return
        checking = true
        Bg.scope.launch {
            try {
                val conn = URL(LATEST).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "AURELIS-Android")
                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val published = Instant.parse(json.getString("published_at")).toEpochMilli()
                    val assets = json.getJSONArray("assets")
                    var url: String? = null
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
                    }
                    // Newer than this build (with a margin for the build that produced this app).
                    available.value = if (url != null && published > BuildConfig.BUILD_TIME + 20 * 60_000L) url else null
                }
                conn.disconnect()
            } catch (e: Exception) {
                // No update info right now; try again next time the app opens.
            } finally {
                checking = false
            }
        }
    }

    fun install(ctx: Context) {
        val url = available.value ?: return
        if (progress.value >= 0) return
        val app = ctx.applicationContext
        progress.value = 0
        Bg.scope.launch {
            try {
                val apk = File(File(app.cacheDir, "update"), "AURELIS.apk")
                Downloader.download(url, apk) { progress.value = it }
                val uri = FileProvider.getUriForFile(app, app.packageName + ".files", apk)
                app.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                AppState.status.value = "Update download failed. Check your internet and try again."
            } finally {
                progress.value = -1
            }
        }
    }
}
