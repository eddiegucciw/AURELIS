package com.aurelis.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** In-app updates: finds the newest build on GitHub and offers it from the Home screen. */
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

    /** Opens the new build's download; Android then installs it over this app, keeping all data. */
    fun install(ctx: Context) {
        val url = available.value ?: return
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppState.status.value = "Couldn't open the update download."
        }
    }
}
