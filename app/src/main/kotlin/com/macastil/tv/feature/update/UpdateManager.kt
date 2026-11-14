package com.macastil.tv.feature.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.macastil.tv.BuildConfig
import org.json.JSONArray
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

    /**
     * Prefijos de asset válidos para esta app. Se ordenan del más nuevo al
     * histórico: los releases antiguos publicaban `app-release.apk`.
     */
    private val ASSET_PREFIXES = listOf("jkanime-tv-")

    suspend fun check(context: Context): AppUpdate? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("jk-updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_check", 0L) < CHECK_INTERVAL_MS) return@withContext null
        prefs.edit().putLong("last_check", now).apply()

        // Se lista en vez de pedir `/releases/latest`: desde 1.4.8 el último
        // release del repositorio puede ser el de móvil. Ver ReleaseSelector.
        runCatching {
            val connection = URL(
                "https://api.github.com/repos/$REPOSITORY/releases?per_page=30",
            ).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "jkanimetv/${BuildConfig.VERSION_NAME}")
            JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                .also { connection.disconnect() }
        }.getOrNull()?.let { releases ->
            val parsed = (0 until releases.length()).mapNotNull { index ->
                val release = releases.optJSONObject(index) ?: return@mapNotNull null
                val assets = release.optJSONArray("assets")
                    ?.let { array ->
                        (0 until array.length()).mapNotNull { assetIndex ->
                            array.optJSONObject(assetIndex)?.let { asset ->
                                ReleaseAsset(
                                    name = asset.optString("name"),
                                    downloadUrl = asset.optString("browser_download_url"),
                                )
                            }
                        }
                    }
                    .orEmpty()

                ReleaseInfo(
                    tag = release.optString("tag_name"),
                    htmlUrl = release.optString("html_url"),
                    assets = assets,
                )
            }

            ReleaseSelector.select(
                releases = parsed,
                assetPrefixes = ASSET_PREFIXES,
                currentVersion = BuildConfig.VERSION_NAME,
            )?.let { selected ->
                AppUpdate(
                    versionName = selected.versionName,
                    downloadUrl = selected.downloadUrl,
                    releaseUrl = selected.releaseUrl,
                )
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

}
