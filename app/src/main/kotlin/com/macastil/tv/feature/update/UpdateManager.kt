package com.macastil.tv.feature.update

import android.content.Context
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

    suspend fun check(context: Context, force: Boolean = false): AppUpdate? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("jk-updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("last_check", 0L) < CHECK_INTERVAL_MS) {
            return@withContext null
        }

        // Se lista en vez de pedir `/releases/latest`: desde 1.4.8 el último
        // release del repositorio puede ser el de móvil. Ver ReleaseSelector.
        //
        // Los errores de red/JSON se propagan a propósito (antes se tragaban
        // con `getOrNull()` y se anunciaba "ya tienes la última versión" aunque
        // la consulta hubiera fallado). La marca de caché se guarda solo si la
        // consulta tuvo éxito.
        val connection = URL(
            "https://api.github.com/repos/$REPOSITORY/releases?per_page=30",
        ).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "jkanimetv/${BuildConfig.VERSION_NAME}")
        val releases = try {
            JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
        prefs.edit().putLong("last_check", now).apply()

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

    /** ¿El sistema permitirá a esta app lanzar el instalador? */
    fun canInstall(context: Context): Boolean = ApkInstaller.canInstall(context)

    /** Ruta local del APK de una versión ya descargada. */
    fun apkFile(context: Context, versionName: String): File =
        File(File(context.cacheDir, "updates"), "jkanimetv-$versionName.apk")

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
}
