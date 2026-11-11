package com.macastil.tv.data.sync

import android.content.Context
import com.macastil.tv.R
import com.macastil.tv.data.local.LibraryRepository
import com.macastil.tv.data.local.ProfileManager

/**
 * Motor de sincronización: pull → merge local → push.
 *
 * El orden importa. Primero se trae lo remoto y se mergea en Room (gana el
 * `updatedAt` más nuevo), y solo después se empuja el estado local ya
 * mergeado. Si se empujara primero, un dispositivo con datos viejos
 * sobrescribiría cambios hechos en otro antes de haberlos visto.
 *
 * El push va protegido además del lado del servidor
 * (`where excluded.updated_at >= tabla.updated_at`), así que un push
 * concurrente nunca pisa algo más nuevo.
 *
 * **Multiusuario (F11):** las credenciales viven en el perfil activo
 * (`ProfileManager`); las de SharedPreferences quedan como modo legacy para
 * dispositivos sin perfiles. Al crear el primer perfil, el espacio hereda
 * automáticamente las credenciales legacy del dispositivo (claim).
 */
class SyncEngine(
    private val library: LibraryRepository,
    private val client: SupabaseSyncClient,
    private val store: SyncCredentialsStore,
    private val profiles: ProfileManager,
    // Solo para localizar mensajes de error que llegan a la UI (menú del
    // avatar / elección de sync), nunca para abrir pantallas.
    context: Context,
) {

    private val appContext = context.applicationContext

    val isConfigured: Boolean get() = client.isConfigured

    /**
     * Credenciales vigentes: las del perfil activo; si el perfil aún no
     * tiene espacio, hereda las legacy del dispositivo (una sola vez); si no
     * hay perfil, las legacy directamente.
     */
    suspend fun currentCredentials(): SyncCredentials? {
        val profile = profiles.activeProfile() ?: return store.credentials
        val code = profile.spaceCode
        val token = profile.spaceToken
        if (code != null && token != null) return SyncCredentials(code, token)

        // Primer perfil del dispositivo: el espacio del dispositivo pasa a
        // ser del perfil y las credenciales legacy dejan de usarse.
        val legacy = store.credentials ?: return null
        profiles.setSpace(profile.id, legacy.code, legacy.token)
        store.credentials = null
        return legacy
    }

    /** Crea un espacio nuevo y lo guarda en el perfil activo (o en legacy). */
    suspend fun createSpace(): SyncCredentials {
        val credentials = client.createSpace()
        val profile = profiles.activeProfile()
        if (profile != null) {
            profiles.setSpace(profile.id, credentials.code, credentials.token)
        } else {
            store.credentials = credentials
        }
        return credentials
    }

    /** Genera un código temporal para vincular otro dispositivo. */
    suspend fun createPairingCode(): PairingCode {
        val credentials = currentCredentials()
            ?: throw IllegalStateException(appContext.getString(R.string.sync_device_not_linked))
        return client.newPairingCode(credentials)
    }

    suspend fun createRecoveryKey(): RecoveryKey {
        val credentials = currentCredentials()
            ?: throw IllegalStateException(appContext.getString(R.string.sync_device_not_linked))
        return client.createRecoveryKey(credentials)
    }

    /**
     * Une este dispositivo a un espacio existente usando solo el código
     * temporal: el token lo entrega el servidor, nunca se teclea.
     */
    suspend fun joinSpace(pairingCode: String) {
        val credentials = client.joinWithCode(pairingCode)
        val profile = profiles.activeProfile()
        if (profile != null) {
            profiles.setSpace(profile.id, credentials.code, credentials.token)
        } else {
            store.credentials = credentials
        }
    }

    suspend fun recoverSpace(recoveryKey: String) {
        val credentials = client.recoverWithKey(recoveryKey)
        val profile = profiles.activeProfile()
        if (profile != null) {
            profiles.setSpace(profile.id, credentials.code, credentials.token)
        } else {
            store.credentials = credentials
        }
    }

    /** Desvincula el espacio del perfil activo (los remotos no se borran). */
    suspend fun clearSpace() {
        val profile = profiles.activeProfile()
        if (profile != null) {
            profiles.clearSpace(profile.id)
        } else {
            store.credentials = null
        }
    }

    suspend fun syncNow(): SyncResult {
        val credentials = currentCredentials()
            ?: return SyncResult.NotPaired
        if (!client.isConfigured) return SyncResult.NotConfigured

        return runCatching {
            val remote = client.pull(credentials)

            remote.favorites.forEach { library.mergeFavorite(it) }
            remote.progress.forEach { library.mergeProgress(it) }

            client.push(
                credentials = credentials,
                favorites = library.favoritesForPush(),
                progress = library.allProgress(),
            )

            store.lastSyncAt = System.currentTimeMillis()
            SyncResult.Success(
                favorites = remote.favorites.size,
                progress = remote.progress.size,
            )
        }.getOrElse { SyncResult.Failure(it.message ?: it::class.java.simpleName) }
    }

    /**
     * Refresca los episodios emitidos de todos los favoritos del perfil
     * activo. Solo se ejecuta si hay credenciales de sync configuradas.
     * No bloquea la UI y se ignora cualquier error (fail-open).
     */
    suspend fun syncFavoritesIfConfigured() {
        val credentials = currentCredentials() ?: return
        if (!client.isConfigured) return

        runCatching {
            val remote = client.pull(credentials)
            // Solo mergeamos favoritos para actualizar last_aired_episode/total/status
            remote.favorites.forEach { library.mergeFavorite(it) }
        }.onFailure { /* fail-open: si falla, la app sigue con datos locales */ }
    }
}

sealed interface SyncResult {
    data class Success(val favorites: Int, val progress: Int) : SyncResult
    data class Failure(val message: String) : SyncResult
    data object NotPaired : SyncResult
    data object NotConfigured : SyncResult
}
