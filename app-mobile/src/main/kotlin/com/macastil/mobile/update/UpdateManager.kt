package com.macastil.mobile.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.macastil.mobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(val versionName: String, val downloadUrl: String)

object UpdateManager {
    private const val REPOSITORY = "gmacastil/jkanime"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    suspend fun check(context: Context, force: Boolean = false): AppUpdate? =
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("jk-mobile-updates", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (!force && now - prefs.getLong("last_check", 0L) < CHECK_INTERVAL_MS) {
                return@withContext null
            }
            prefs.edit().putLong("last_check", now).apply()

            runCatching {
                val connection = URL(
                    "https://api.github.com/repos/$REPOSITORY/releases?per_page=30",
                ).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "jkanime-mobile/${BuildConfig.VERSION_NAME}")
                val releases = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                connection.disconnect()

                var bestVersion: String? = null
                var bestUrl: String? = null
                for (index in 0 until releases.length()) {
                    val release = releases.optJSONObject(index) ?: continue
                    val version = baseVersion(release.optString("tag_name")) ?: continue
                    val assets = release.optJSONArray("assets") ?: continue
                    for (assetIndex in 0 until assets.length()) {
                        val asset = assets.optJSONObject(assetIndex) ?: continue
                        val name = asset.optString("name")
                        if (!name.startsWith("jkanime-mobile-") || !name.endsWith(".apk")) continue
                        if (bestVersion == null || compareVersions(version, bestVersion!!) > 0) {
                            bestVersion = version
                            bestUrl = asset.optString("browser_download_url")
                        }
                    }
                }
                if (bestVersion != null && bestUrl.orEmpty().isNotBlank() &&
                    compareVersions(bestVersion!!, BuildConfig.VERSION_NAME) > 0
                ) {
                    AppUpdate(bestVersion!!, bestUrl!!)
                } else {
                    null
                }
            }.getOrNull()
        }

    suspend fun downloadAndInstall(context: Context, update: AppUpdate) =
        withContext(Dispatchers.IO) {
            val apk = File(context.cacheDir, "updates/jkanime-mobile-${update.versionName}.apk")
                .apply { parentFile?.mkdirs() }
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
            withContext(Dispatchers.Main) { context.startActivity(intent) }
        }

    private fun baseVersion(tag: String): String? {
        val value = tag.trim().removePrefix("v").removePrefix("V")
            .removeSuffix("-mobile").removeSuffix("-tv")
        val parts = value.split('.')
        return value.takeIf { parts.isNotEmpty() && parts.all { it.toIntOrNull() != null } }
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.split('.').mapNotNull { it.toIntOrNull() }
        val b = right.split('.').mapNotNull { it.toIntOrNull() }
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(index) { 0 }
            val y = b.getOrElse(index) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
