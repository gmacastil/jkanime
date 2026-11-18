package com.macastil.mobile.update

import android.content.Context
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

            // Los errores de red/JSON se propagan a propósito: antes se
            // tragaban con `getOrNull()` y la pantalla decía "ya tienes la
            // última versión" aunque no se hubiera podido consultar. Tampoco
            // se marca la comprobación como hecha si falla, para no dejar la
            // caché de 6 h envenenada.
            val connection = URL(
                "https://api.github.com/repos/$REPOSITORY/releases?per_page=30",
            ).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "jkanime-mobile/${BuildConfig.VERSION_NAME}")
            val releases = try {
                JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
            prefs.edit().putLong("last_check", now).apply()

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
        }

    /** ¿El sistema permitirá a esta app lanzar el instalador? */
    fun canInstall(context: Context): Boolean = ApkInstaller.canInstall(context)

    /** Ruta local del APK de una versión ya descargada. */
    fun apkFile(context: Context, versionName: String): File =
        File(File(context.cacheDir, "updates"), "jkanime-mobile-$versionName.apk")

    suspend fun downloadAndInstall(context: Context, update: AppUpdate): InstallLaunch =
        withContext(Dispatchers.IO) {
            val apk = apkFile(context, update.versionName)
            apk.parentFile?.mkdirs()
            URL(update.downloadUrl).openStream().use { input ->
                apk.outputStream().use { output -> input.copyTo(output) }
            }
            // Si el permiso faltaba, el APK queda en caché y este reintento no
            // vuelve a descargar nada.
            ApkInstaller.launch(context, apk)
        }

    /** Reintenta instalar un APK ya descargado (p. ej. al volver de Ajustes). */
    suspend fun installDownloaded(context: Context, update: AppUpdate): InstallLaunch =
        withContext(Dispatchers.IO) {
            ApkInstaller.launch(context, apkFile(context, update.versionName))
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
