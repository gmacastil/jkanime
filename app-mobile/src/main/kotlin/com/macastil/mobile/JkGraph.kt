package com.macastil.mobile

import android.content.Context
import com.macastil.mobile.data.local.LibraryRepository
import com.macastil.mobile.data.sync.SyncEngine
import com.macastil.mobile.data.sync.createSyncEngine
import com.macastil.tv.catalog.JkanimeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * DI manual de `:app-mobile` (mismo patrón que `Graph` en `:app`).
 *
 * Todo se instancia **una vez** por proceso. Antes cada composable creaba su
 * propio `JkanimeRepository()` (y con él un `OkHttpClient` con pool de hilos y
 * cookie jar nuevos) en cada recomposición: fugas de hilos, cookies de CSRF
 * distintas por pantalla y cuelgues al navegar.
 */
object JkGraph {

    @Volatile
    private var ready = false

    lateinit var repository: JkanimeRepository
        private set

    lateinit var library: LibraryRepository
        private set

    lateinit var syncEngine: SyncEngine
        private set

    /** Scope de vida del proceso: sobrevive a la navegación. */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    fun init(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val app = context.applicationContext
            repository = JkanimeRepository()
            library = LibraryRepository(app)
            syncEngine = createSyncEngine(app, library)
            ready = true
        }
    }
}
