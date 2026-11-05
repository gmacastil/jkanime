package net.jkanime.tv.data.sync

import net.jkanime.tv.data.local.LibraryRepository

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
 */
class SyncEngine(
    private val library: LibraryRepository,
    private val client: SupabaseSyncClient,
    private val store: SyncCredentialsStore,
) {

    val isConfigured: Boolean get() = client.isConfigured

    fun currentCredentials(): SyncCredentials? = store.credentials

    /** Crea un espacio nuevo y lo guarda en este dispositivo. */
    suspend fun createSpace(): SyncCredentials {
        val credentials = client.createSpace()
        store.credentials = credentials
        return credentials
    }

    /** Une este dispositivo a un espacio existente. */
    suspend fun joinSpace(code: String, token: String) {
        val credentials = SyncCredentials(code.trim(), token.trim())
        // Se valida con un pull real: si el código/token no sirven, el
        // servidor lanza y no se guarda basura en SharedPreferences.
        client.pull(credentials)
        store.credentials = credentials
    }

    /** Desvincula este dispositivo (los datos remotos no se borran). */
    fun clearSpace() {
        store.credentials = null
    }

    suspend fun syncNow(): SyncResult {
        val credentials = store.credentials
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
}

sealed interface SyncResult {
    data class Success(val favorites: Int, val progress: Int) : SyncResult
    data class Failure(val message: String) : SyncResult
    data object NotPaired : SyncResult
    data object NotConfigured : SyncResult
}
