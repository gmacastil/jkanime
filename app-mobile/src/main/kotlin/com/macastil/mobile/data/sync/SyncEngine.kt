package com.macastil.mobile.data.sync

import android.content.Context
import com.macastil.mobile.data.local.FavoriteEntity
import com.macastil.mobile.data.local.LibraryRepository
import com.macastil.mobile.data.local.ProgressEntity
import com.macastil.mobile.data.local.SyncCredentialsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Resultado de una sincronización.
 */
sealed interface SyncResult {
    data object NotPaired : SyncResult
    data object NotConfigured : SyncResult
    data class Success(val favorites: Int, val progress: Int) : SyncResult
    data class Failure(val message: String) : SyncResult
}

/**
 * Motor de sincronización multi-dispositivo para app-mobile.
 */
class SyncEngine(
    private val library: LibraryRepository,
    private val client: SupabaseSyncClient,
    private val context: android.content.Context,
) {

    private val appScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun currentCredentials(): SyncCredentials? {
        return runBlocking {
            library.getCredentials()?.let { SyncCredentials(it.code, it.token) }
        }
    }

    suspend fun createSpace(): SyncCredentials {
        val credentials = client.createSpace()
        runBlocking {
            library.setCredentials(credentials.code, credentials.token)
        }
        return credentials
    }

    suspend fun createPairingCode(): PairingCode {
        val credentials = currentCredentials()
            ?: throw IllegalStateException("No hay espacio vinculado")
        return client.newPairingCode(credentials)
    }

    suspend fun setRecoveryKey(recoveryKey: String): RecoveryKey {
        val credentials = currentCredentials()
            ?: throw IllegalStateException("No hay espacio vinculado")
        return client.setRecoveryKey(credentials, recoveryKey)
    }

    suspend fun joinSpace(pairingCode: String) {
        val credentials = client.joinWithCode(pairingCode)
        runBlocking {
            library.setCredentials(credentials.code, credentials.token)
        }
    }

    suspend fun recoverSpace(recoveryKey: String) {
        val credentials = client.recoverWithKey(recoveryKey)
        runBlocking {
            library.setCredentials(credentials.code, credentials.token)
        }
    }

    suspend fun clearSpace() {
        runBlocking {
            library.clearCredentials()
        }
    }

    suspend fun syncNow(): SyncResult {
        val credentials = currentCredentials()
            ?: return SyncResult.NotPaired
        if (!client.isConfigured) return SyncResult.NotConfigured

        return runCatching {
            val remote = client.pull(credentials)

            // Merge favoritos remotos
            remote.favorites.forEach { fav ->
                val existing = runBlocking { library.getAllFavorites().firstOrNull { it.slug == fav.slug } }
                val now = System.currentTimeMillis()
                if (existing == null || existing.deletedAt != null) {
                    runBlocking { library.toggleFavorite(fav.slug, fav.title, fav.posterUrl, fav.animeId, fav.statusLabel) }
                } else if (fav.updatedAt > (existing.updatedAt ?: 0)) {
                    runBlocking { library.toggleFavorite(fav.slug, fav.title, fav.posterUrl, fav.animeId, fav.statusLabel) }
                }
            }

            // Push favoritos locales
            val localFavorites = runBlocking { library.getAllFavorites() }
            val localProgress = runBlocking { library.getAllProgress() }

            val pushed = client.push(credentials, localFavorites, localProgress)

            SyncResult.Success(
                favorites = pushed.favorites.size,
                progress = pushed.progress.size,
            )
        }.getOrElse { SyncResult.Failure(it.message ?: it::class.java.simpleName) }
    }

    fun syncInBackground(context: android.content.Context) {
        appScope.launch {
            runCatching { syncNow() }
        }
    }
}

/** Función de fábrica para crear el motor de sync. */
fun createSyncEngine(context: android.content.Context): SyncEngine {
    val library = LibraryRepository()
    val client = createSyncClient(context)
    return SyncEngine(
        library = library,
        client = client,
        context = context,
    )
}
