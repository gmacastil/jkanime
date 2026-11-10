package com.macastil.tv

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.macastil.tv.catalog.JkanimeRepository
import com.macastil.tv.data.local.JkDatabase
import com.macastil.tv.data.local.LibraryRepository
import com.macastil.tv.data.local.ProfileManager
import com.macastil.tv.data.sync.SyncCredentialsStore
import com.macastil.tv.data.sync.SyncEngine

/**
 * DI manual. Deliberado: la app tiene pocas dependencias reales y Hilt solo
 * añadiría tiempo de build.
 */
object Graph {
    val repository: JkanimeRepository by lazy { JkanimeRepository() }

    /**
     * Scope de vida de la app para trabajo "fire and forget" que no debe
     * cancelarse cuando una pantalla sale de composición (p. ej. el sync al
     * entrar a un perfil: la puerta de entrada navega a Home de inmediato,
     * pero el pull/push debe seguir corriendo en background).
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Dispara un pull/push en background; no bloquea ni se cancela al navegar. */
    fun syncInBackground(context: Context) {
        appScope.launch { runCatching { sync(context).syncNow() } }
    }

    @Volatile
    private var profileManager: ProfileManager? = null

    /** Perfiles locales (F11). Comparte la DB singleton con `library`. */
    fun profiles(context: Context): ProfileManager =
        profileManager ?: synchronized(this) {
            profileManager ?: ProfileManager(context, JkDatabase.get(context))
                .also { profileManager = it }
        }

    @Volatile
    private var libraryRepository: LibraryRepository? = null

    /** Favoritos + progreso (Room). Requiere `Context` para abrir la DB. */
    fun library(context: Context): LibraryRepository =
        libraryRepository ?: synchronized(this) {
            libraryRepository ?: LibraryRepository(
                db = JkDatabase.get(context),
                profiles = profiles(context),
            ).also { libraryRepository = it }
        }

    @Volatile
    private var syncEngine: SyncEngine? = null

    /** Sync multi-dispositivo (Supabase). Comparte la DB con `library`. */
    fun sync(context: Context): SyncEngine =
        syncEngine ?: synchronized(this) {
            syncEngine ?: SyncEngine(
                library = library(context),
                client = SyncCredentialsStore.client(context),
                store = SyncCredentialsStore(context),
                profiles = profiles(context),
                context = context,
            ).also { syncEngine = it }
        }
}
