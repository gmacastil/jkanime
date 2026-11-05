package net.jkanime.tv

import android.content.Context
import net.jkanime.tv.catalog.JkanimeRepository
import net.jkanime.tv.data.local.JkDatabase
import net.jkanime.tv.data.local.LibraryRepository

/**
 * DI manual. Deliberado: la app tiene pocas dependencias reales y Hilt solo
 * añadiría tiempo de build.
 */
object Graph {
    val repository: JkanimeRepository by lazy { JkanimeRepository() }

    @Volatile
    private var libraryRepository: LibraryRepository? = null

    /** Favoritos + progreso (Room). Requiere `Context` para abrir la DB. */
    fun library(context: Context): LibraryRepository =
        libraryRepository ?: synchronized(this) {
            libraryRepository ?: LibraryRepository(JkDatabase.get(context))
                .also { libraryRepository = it }
        }
}
