package net.jkanime.tv.data.local

import kotlinx.coroutines.flow.Flow

/**
 * Fachada de persistencia local (favoritos + progreso). Los ViewModels solo
 * ven esta clase, nunca los DAOs, para que un cambio de motor (Room → otra
 * cosa) no toque la UI.
 *
 * `merge*` son las entradas que usa el motor de sync: aplican datos remotos
 * solo si son más nuevos, para que un dispositivo offline no pise cambios
 * hechos en otro.
 */
class LibraryRepository(private val db: JkDatabase) {

    val favorites: Flow<List<FavoriteEntity>> = db.favorites().observeAll()

    fun isFavorite(slug: String): Flow<Boolean> = db.favorites().observeIsFavorite(slug)

    fun favorite(slug: String): Flow<FavoriteEntity?> = db.favorites().observe(slug)

    fun progressFor(slug: String): Flow<List<ProgressEntity>> = db.progress().observeFor(slug)

    /** Instantánea de favoritos para el worker de sync (no es un Flow). */
    suspend fun favoritesSnapshot(): List<FavoriteEntity> = db.favorites().all()

    /** Incluye tombstones: es lo que se empuja al backend. */
    suspend fun favoritesForPush(): List<FavoriteEntity> = db.favorites().allIncludingDeleted()

    suspend fun allProgress(): List<ProgressEntity> = db.progress().all()

    suspend fun lastProgress(slug: String): ProgressEntity? = db.progress().lastFor(slug)

    suspend fun episodeProgress(slug: String, episode: Int): ProgressEntity? =
        db.progress().get(slug, episode)

    suspend fun toggleFavorite(
        slug: String,
        animeId: Int,
        title: String,
        posterUrl: String?,
        totalEpisodes: Int?,
        statusLabel: String?,
        lastAiredEpisode: Int?,
    ): Boolean {
        val dao = db.favorites()
        val now = System.currentTimeMillis()
        val existing = dao.get(slug)
        val isAlive = existing != null && existing.deletedAt == null
        return if (isAlive) {
            dao.softDelete(slug, now)
            false
        } else {
            // Si existe como tombstone se reusa la fila: si se insertara otra
            // con el mismo slug, el merge remoto la resucitaría con los datos
            // viejos.
            dao.upsert(
                FavoriteEntity(
                    slug = slug,
                    animeId = animeId,
                    title = title,
                    posterUrl = posterUrl,
                    addedAt = existing?.addedAt ?: now,
                    lastWatchedEpisode = existing?.lastWatchedEpisode,
                    lastAiredEpisode = lastAiredEpisode,
                    totalEpisodes = totalEpisodes,
                    statusLabel = statusLabel,
                    syncedAt = now,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
            true
        }
    }

    /** Refresca datos de emisión de un favorito (usado por el sync de F5). */
    suspend fun updateAired(
        slug: String,
        lastAiredEpisode: Int?,
        totalEpisodes: Int?,
        statusLabel: String?,
    ) {
        db.favorites().setAired(
            slug = slug,
            aired = lastAiredEpisode,
            total = totalEpisodes,
            status = statusLabel,
            now = System.currentTimeMillis(),
        )
    }

    /**
     * Guarda progreso. Si el episodio ya estaba completado no se degrada a
     * "a medias" por un `timeupdate` tardío del WebView.
     */
    suspend fun saveProgress(
        slug: String,
        episode: Int,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean,
    ) {
        val previous = db.progress().get(slug, episode)
        val now = System.currentTimeMillis()
        val finalCompleted = completed || previous?.completed == true
        db.progress().upsert(
            ProgressEntity(
                slug = slug,
                episode = episode,
                positionMs = if (finalCompleted) durationMs.coerceAtLeast(positionMs) else positionMs,
                durationMs = durationMs,
                completed = finalCompleted,
                updatedAt = now,
            ),
        )
        if (completed) {
            db.favorites().setLastWatched(slug, episode, now)
        }
    }

    // ---------------------------------------------------------------------
    // Merge desde el backend (last-write-wins por `updatedAt`)
    // ---------------------------------------------------------------------

    suspend fun mergeFavorite(remote: FavoriteEntity) {
        val local = db.favorites().get(remote.slug)
        if (local != null && local.updatedAt > remote.updatedAt) return
        db.favorites().upsert(remote)
    }

    suspend fun mergeProgress(remote: ProgressEntity) {
        val local = db.progress().get(remote.slug, remote.episode)
        if (local != null && local.updatedAt > remote.updatedAt) return
        db.progress().upsert(remote)
    }
}
