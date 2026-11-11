package com.macastil.tv.data.local

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Fachada de persistencia local (favoritos + progreso). Los ViewModels solo
 * ven esta clase, nunca los DAOs, para que un cambio de motor (Room → otra
 * cosa) no toque la UI.
 *
 * `merge*` son las entradas que usa el motor de sync: aplican datos remotos
 * solo si son más nuevos, para que un dispositivo offline no pise cambios
 * hechos en otro.
 *
 * **Multiusuario (F11):** todas las lecturas se suscriben a
 * `ProfileManager.activeId` con `flatMapLatest`, así al cambiar de perfil en
 * la misma TV los Flows re-emiten con la biblioteca del nuevo dueño sin
 * tocar los ViewModels. Las escrituras usan `owner()` (el activo en ese
 * instante).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepository(
    private val db: JkDatabase,
    private val profiles: ProfileManager,
) {

    val favorites: Flow<List<FavoriteEntity>> =
        profiles.activeId.flatMapLatest { owner -> db.favorites().observeAll(owner) }

    fun isFavorite(slug: String): Flow<Boolean> =
        profiles.activeId.flatMapLatest { owner -> db.favorites().observeIsFavorite(owner, slug) }

    fun favorite(slug: String): Flow<FavoriteEntity?> =
        profiles.activeId.flatMapLatest { owner -> db.favorites().observe(owner, slug) }

    fun progressFor(slug: String): Flow<List<ProgressEntity>> =
        profiles.activeId.flatMapLatest { owner -> db.progress().observeFor(owner, slug) }

    /** Instantánea de favoritos para el worker de sync (no es un Flow). */
    suspend fun favoritesSnapshot(): List<FavoriteEntity> =
        db.favorites().all(profiles.owner())

    /** Incluye tombstones: es lo que se empuja al backend. */
    suspend fun favoritesForPush(): List<FavoriteEntity> =
        db.favorites().allIncludingDeleted(profiles.owner())

    suspend fun allProgress(): List<ProgressEntity> = db.progress().all(profiles.owner())

    suspend fun lastProgress(slug: String): ProgressEntity? =
        db.progress().lastFor(profiles.owner(), slug)

    suspend fun episodeProgress(slug: String, episode: Int): ProgressEntity? =
        db.progress().get(profiles.owner(), slug, episode)

    suspend fun toggleFavorite(
        slug: String,
        animeId: Int,
        title: String,
        posterUrl: String?,
        totalEpisodes: Int?,
        statusLabel: String?,
        lastAiredEpisode: Int?,
    ): Boolean {
        val owner = profiles.owner()
        val dao = db.favorites()
        val now = System.currentTimeMillis()
        val existing = dao.get(owner, slug)
        val isAlive = existing != null && existing.deletedAt == null
        return if (isAlive) {
            dao.softDelete(owner, slug, now)
            false
        } else {
            // Si existe como tombstone se reusa la fila: si se insertara otra
            // con el mismo slug, el merge remoto la resucitaría con los datos
            // viejos.
            dao.upsert(
                FavoriteEntity(
                    owner = owner,
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
            owner = profiles.owner(),
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
        val owner = profiles.owner()
        val previous = db.progress().get(owner, slug, episode)
        val now = System.currentTimeMillis()
        val finalCompleted = completed || previous?.completed == true
        db.progress().upsert(
            ProgressEntity(
                owner = owner,
                slug = slug,
                episode = episode,
                positionMs = if (finalCompleted) durationMs.coerceAtLeast(positionMs) else positionMs,
                durationMs = durationMs,
                completed = finalCompleted,
                updatedAt = now,
            ),
        )
        if (completed) {
            db.favorites().setLastWatched(owner, slug, episode, now)
        }
    }

    // ---------------------------------------------------------------------
    // Marcado manual de capítulos (pulsación sostenida y "todo visto")
    // ---------------------------------------------------------------------

    /**
     * Marca un capítulo como visto sin haberlo reproducido. Si ya había
     * progreso se conserva su duración para que la barra no quede rara.
     */
    suspend fun markWatched(slug: String, episode: Int) {
        val owner = profiles.owner()
        val previous = db.progress().get(owner, slug, episode)
        val duration = previous?.durationMs ?: 0L
        val now = System.currentTimeMillis()
        db.progress().upsert(
            ProgressEntity(
                owner = owner,
                slug = slug,
                episode = episode,
                positionMs = duration,
                durationMs = duration,
                completed = true,
                updatedAt = now,
            ),
        )
        db.favorites().bumpLastWatched(owner, slug, episode, now)
    }

    /** Desmarca un capítulo y recalcula el último visto del favorito. */
    suspend fun unmarkWatched(slug: String, episode: Int) {
        val owner = profiles.owner()
        db.progress().delete(owner, slug, episode)
        db.favorites().setLastWatched(
            owner = owner,
            slug = slug,
            episode = db.progress().maxCompleted(owner, slug),
            now = System.currentTimeMillis(),
        )
    }

    /** "Todo visto": marca completos todos los capítulos indicados. */
    suspend fun markAllWatched(slug: String, episodes: List<Int>) {
        if (episodes.isEmpty()) return
        val owner = profiles.owner()
        val now = System.currentTimeMillis()
        episodes.forEach { episode ->
            val previous = db.progress().get(owner, slug, episode)
            val duration = previous?.durationMs ?: 0L
            db.progress().upsert(
                ProgressEntity(
                    owner = owner,
                    slug = slug,
                    episode = episode,
                    positionMs = duration,
                    durationMs = duration,
                    completed = true,
                    updatedAt = now,
                ),
            )
        }
        db.favorites().bumpLastWatched(owner, slug, episodes.max(), now)
    }

    /** Desmarca todos los capítulos indicados y recalcula el último visto. */
    suspend fun unmarkAllWatched(slug: String, episodes: List<Int>) {
        if (episodes.isEmpty()) return
        val owner = profiles.owner()
        episodes.forEach { episode ->
            db.progress().delete(owner, slug, episode)
        }
        db.favorites().setLastWatched(
            owner = owner,
            slug = slug,
            episode = db.progress().maxCompleted(owner, slug),
            now = System.currentTimeMillis(),
        )
    }

    // ---------------------------------------------------------------------
    // Merge desde el backend (last-write-wins por `updatedAt`)
    // ---------------------------------------------------------------------

    suspend fun mergeFavorite(remote: FavoriteEntity) {
        val owner = profiles.owner()
        val local = db.favorites().get(owner, remote.slug)
        if (local != null && local.updatedAt > remote.updatedAt) return
        // El remoto viene del espacio del perfil activo: se adopta con el
        // owner actual, nunca con el que traiga el JSON.
        db.favorites().upsert(remote.copy(owner = owner))
    }

    suspend fun mergeProgress(remote: ProgressEntity) {
        val owner = profiles.owner()
        val local = db.progress().get(owner, remote.slug, remote.episode)
        if (local != null && local.updatedAt > remote.updatedAt) return
        db.progress().upsert(remote.copy(owner = owner))
    }
}
