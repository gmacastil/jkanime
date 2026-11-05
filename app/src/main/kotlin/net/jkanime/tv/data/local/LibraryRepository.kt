package net.jkanime.tv.data.local

import kotlinx.coroutines.flow.Flow

/**
 * Fachada de persistencia local (favoritos + progreso). Los ViewModels solo
 * ven esta clase, nunca los DAOs, para que un cambio de motor (Room → otra
 * cosa) no toque la UI.
 */
class LibraryRepository(private val db: JkDatabase) {

    val favorites: Flow<List<FavoriteEntity>> = db.favorites().observeAll()

    fun isFavorite(slug: String): Flow<Boolean> = db.favorites().observeIsFavorite(slug)

    fun favorite(slug: String): Flow<FavoriteEntity?> = db.favorites().observe(slug)

    fun progressFor(slug: String): Flow<List<ProgressEntity>> = db.progress().observeFor(slug)

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
        val existing = dao.all().firstOrNull { it.slug == slug }
        return if (existing != null) {
            dao.delete(slug)
            false
        } else {
            dao.upsert(
                FavoriteEntity(
                    slug = slug,
                    animeId = animeId,
                    title = title,
                    posterUrl = posterUrl,
                    addedAt = System.currentTimeMillis(),
                    lastWatchedEpisode = null,
                    lastAiredEpisode = lastAiredEpisode,
                    totalEpisodes = totalEpisodes,
                    statusLabel = statusLabel,
                    syncedAt = System.currentTimeMillis(),
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
            db.favorites().setLastWatched(slug, episode)
        }
    }
}
