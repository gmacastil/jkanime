package com.macastil.mobile.update

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.FileProvider
import com.macastil.mobile.BuildConfig
import java.io.File

/** Qué pasó al intentar lanzar el instalador del sistema. */
enum class InstallLaunch {
    /** El instalador del sistema se abrió con el APK. */
    STARTED,

    /** Falta conceder "instalar apps desconocidas": se abrieron los ajustes. */
    NEEDS_PERMISSION,

    /** El APK no está, o no hay ninguna pantalla del sistema que lo instale. */
    NO_INSTALLER,
}

/**
 * Lanzamiento del instalador de un APK descargado.
 *
 * Desde Android 8 el sistema exige que el usuario conceda *a esta app* el
 * permiso "instalar apps desconocidas" (`REQUEST_INSTALL_PACKAGES`, que es un
 * app-op concedido a mano). Si no está concedido, el instalador abre un aviso
 * sin salida y la actualización queda a medias. Aquí se comprueba antes y, si
 * falta, se manda al usuario a la pantalla exacta.
 */
/** App-op de "instalar apps desconocidas" (no hay constante pública en el SDK). */
private const val OP_INSTALL_PACKAGES = "android:request_install_packages"

object ApkInstaller {

    /**
     * ¿El sistema permitirá a esta app lanzar el instalador?
     *
     * No basta `PackageManager.canRequestPackageInstalls()`: con el app-op en
     * `default` hay plataformas que contestan `true` y luego el instalador
     * rechaza el APK igualmente. Se consulta el modo real del app-op, que es lo
     * único que el usuario puede poner en `allow` desde Ajustes.
     */
    fun canInstall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return context.packageManager.canRequestPackageInstalls()
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(
                    OP_INSTALL_PACKAGES,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(
                    OP_INSTALL_PACKAGES,
                    Process.myUid(),
                    context.packageName,
                )
            }
        }.getOrNull()
        // `default` se trata como denegado a propósito: es el estado en el que
        // el instalador muestra su aviso sin salida.
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Abre la pantalla del sistema para instalar un APK local. */
    fun launch(context: Context, apk: File): InstallLaunch {
        if (!apk.isFile || apk.length() <= 0L) return InstallLaunch.NO_INSTALLER
        if (!canInstall(context)) {
            return if (openPermissionSettings(context)) {
                InstallLaunch.NEEDS_PERMISSION
            } else {
                InstallLaunch.NO_INSTALLER
            }
        }

        val uri = try {
            FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                apk,
            )
        } catch (error: IllegalArgumentException) {
            // El APK vive fuera de los `paths` del FileProvider: el instalador
            // nunca lo vería, así que se informa en vez de fallar en silencio.
            return InstallLaunch.NO_INSTALLER
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolvable = context.packageManager.resolveActivity(intent, 0) != null
        if (!resolvable) return InstallLaunch.NO_INSTALLER
        return runCatching {
            context.startActivity(intent)
            InstallLaunch.STARTED
        }.getOrDefault(InstallLaunch.NO_INSTALLER)
    }

    /**
     * Ajustes para conceder el permiso. En algunos fabricantes el camino corto
     * (`ACTION_MANAGE_UNKNOWN_APP_SOURCES`) no existe, así que se degrada a los
     * ajustes de aplicaciones y, al final, a los generales.
     */
    fun openPermissionSettings(context: Context): Boolean {
        val packageUri = Uri.fromParts("package", context.packageName, null)
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri))
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
            }
            add(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (context.packageManager.resolveActivity(intent, 0) == null) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }
}
