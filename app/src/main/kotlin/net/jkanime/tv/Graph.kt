package net.jkanime.tv

import android.content.Context
import net.jkanime.tv.catalog.JkanimeRepository
import net.jkanime.tv.data.local.JkDatabase
import net.jkanime.tv.data.local.LibraryRepository
import net.jkanime.tv.data.local.ProfileManager
import net.jkanime.tv.data.sync.SyncCredentialsStore
import net.jkanime.tv.data.sync.SyncEngine

/**
 * DI manual. Deliberado: la app tiene pocas dependencias reales y Hilt solo
 * añadiría tiempo de build.
 */
object Graph {
    val repository: JkanimeRepository by lazy { JkanimeRepository() }

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
                client = SyncCredentialsStore.client(),
                store = SyncCredentialsStore(context),
                profiles = profiles(context),
            ).also { syncEngine = it }
        }
}
