package com.macastil.mobile.data.sync

import android.content.Context
import com.macastil.mobile.R
import com.macastil.mobile.data.local.FavoriteEntity
import com.macastil.mobile.data.local.LibraryRepository
import com.macastil.mobile.data.local.ProgressEntity
import com.macastil.mobile.data.local.SyncCredentialsEntity
import com.macastil.tv.model.normalizePairingCode
import com.macastil.tv.model.normalizeRecoveryKey
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

    /**
     * [error] viaja aparte de [message] para que la pantalla pueda formatear el
     * texto con el idioma del perfil (`@StringRes`) en vez de con el del
     * Application que capturó el motor.
     */
    data class Failure(val message: String, val error: SyncException? = null) : SyncResult
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
            ?: throw SyncException(res = R.string.sync_msg_need_space)
        return client.newPairingCode(credentials)
    }

    suspend fun setRecoveryKey(recoveryKey: String): RecoveryKey {
        val credentials = currentCredentials()
            ?: throw SyncException(res = R.string.sync_msg_need_space)
        return client.setRecoveryKey(credentials, normalizeRecoveryKey(recoveryKey))
    }

    suspend fun joinSpace(pairingCode: String) {
        val credentials = client.joinWithCode(normalizePairingCode(pairingCode))
        runBlocking {
            library.setCredentials(credentials.code, credentials.token)
        }
    }

    suspend fun recoverSpace(recoveryKey: String) {
        val credentials = client.recoverWithKey(normalizeRecoveryKey(recoveryKey))
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
            // 1) pull: traer la foto remota
            val remote = client.pull(credentials)

            // 2) merge local: last-write-wins por `updatedAt`, con tombstones
            remote.favorites.forEach { library.upsertFavorite(it) }
            remote.progress.forEach { library.upsertProgress(it) }

            // 3) push: subir la biblioteca ya mergeada
            val localFavorites = library.getAllFavorites()
            val localProgress = library.getAllProgress()
            val pushed = client.push(credentials, localFavorites, localProgress)

            SyncResult.Success(
                favorites = pushed.favorites,
                progress = pushed.progress,
            )
        }.getOrElse {
            SyncResult.Failure(
                message = it.message ?: it::class.java.simpleName,
                error = it as? SyncException,
            )
        }
    }

    fun syncInBackground(context: android.content.Context) {
        appScope.launch {
            runCatching { syncNow() }
        }
    }
}

/** Función de fábrica para crear el motor de sync. */
fun createSyncEngine(
    context: android.content.Context,
    library: LibraryRepository = LibraryRepository(context),
): SyncEngine {
    val client = createSyncClient(context)
    return SyncEngine(
        library = library,
        client = client,
        context = context,
    )
}
