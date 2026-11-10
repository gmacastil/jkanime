package com.macastil.tv.feature.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.macastil.tv.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(
    val versionName: String,
    val downloadUrl: String,
    val releaseUrl: String,
)

object UpdateManager {
    private const val REPOSITORY = "gmacastil/jkanime"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    suspend fun check(context: Context): AppUpdate? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("jk-updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_check", 0L) < CHECK_INTERVAL_MS) return@withContext null
        prefs.edit().putLong("last_check", now).apply()

        runCatching {
            val connection = URL(
                "https://api.github.com/repos/$REPOSITORY/releases/latest",
            ).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "jkanimetv/${BuildConfig.VERSION_NAME}")
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                .also { connection.disconnect() }
        }.getOrNull()?.let { release ->
            val version = release.optString("tag_name").removePrefix("v")
            val apk = release.optJSONArray("assets")?.let { assets ->
                (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") }
            }
            if (apk != null && isNewer(version, BuildConfig.VERSION_NAME)) {
                AppUpdate(
                    versionName = version,
                    downloadUrl = apk.optString("browser_download_url"),
                    releaseUrl = release.optString("html_url"),
                )
            } else {
                null
            }
        }
    }

    suspend fun downloadAndInstall(context: Context, update: AppUpdate) = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(updatesDir, "jkanimetv-${update.versionName}.apk")
        URL(update.downloadUrl).openStream().use { input ->
            apk.outputStream().use { output -> input.copyTo(output) }
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        withContext(Dispatchers.Main) {
            context.startActivity(intent)
        }
    }

    private fun isNewer(candidate: String, current: String): Boolean {
        val left = candidate.split('.').mapNotNull { it.toIntOrNull() }
        val right = current.split('.').mapNotNull { it.toIntOrNull() }
        if (left.isEmpty() || right.isEmpty()) return false
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
