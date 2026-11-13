package com.macastil.mobile.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Repositorio de biblioteca para app-mobile (implementación en memoria).
 * Los datos se mantienen mientras vive el proceso de la app.
 */
class LibraryRepository {
    private val _favorites = MutableStateFlow<List<FavoriteEntity>>(emptyList())
    val favorites: Flow<List<FavoriteEntity>> = _favorites.asStateFlow()

    private val _progress = MutableStateFlow<List<ProgressEntity>>(emptyList())
    val progress: Flow<List<ProgressEntity>> = _progress.asStateFlow()

    private val _credentials = MutableStateFlow<SyncCredentialsEntity?>(null)
    val credentials: Flow<SyncCredentialsEntity?> = _credentials.asStateFlow()

    suspend fun isFavorite(slug: String): Boolean =
        _favorites.value.any { it.slug == slug && it.deletedAt == null }

    suspend fun toggleFavorite(slug: String, title: String, posterUrl: String?,
                               animeId: Int = 0, statusLabel: String? = null) {
        val now = System.currentTimeMillis()
        val existing = _favorites.value.firstOrNull { it.slug == slug }
        if (existing != null && existing.deletedAt == null) {
            // Alternar: quitar favorito (soft delete)
            _favorites.value = _favorites.value.map {
                if (it.slug == slug) it.copy(deletedAt = now, updatedAt = now) else it
            }
        } else {
            // Añadir o reactivar favorito
            _favorites.value = _favorites.value.filter { it.slug != slug }
            _favorites.value += FavoriteEntity(
                slug = slug,
                title = title,
                animeId = animeId,
                posterUrl = posterUrl,
                addedAt = existing?.addedAt ?: now,
                lastWatchedEpisode = existing?.lastWatchedEpisode,
                lastAiredEpisode = existing?.lastAiredEpisode,
                totalEpisodes = existing?.totalEpisodes,
                statusLabel = statusLabel ?: existing?.statusLabel,
                syncedAt = existing?.syncedAt ?: now,
                updatedAt = now,
            )
        }
    }

    suspend fun markWatched(slug: String, episode: Int) {
        val existing = _progress.value.firstOrNull { it.slug == slug && it.episode == episode }
        val now = System.currentTimeMillis()
        val previous = existing
        val duration = previous?.durationMs ?: 0L
        _progress.value = _progress.value.map { p ->
            if (p.slug == slug && p.episode == episode) p.copy(
                positionMs = duration, durationMs = duration,
                completed = true, updatedAt = now,
            ) else p
        }
        if (_progress.value.none { it.slug == slug && it.episode == episode }) {
            _progress.value += ProgressEntity(
                slug = slug, episode = episode,
                positionMs = duration, durationMs = duration,
                completed = true, updatedAt = now,
            )
        }
    }

    suspend fun unmarkWatched(slug: String, episode: Int) {
        _progress.value = _progress.value.filter { !(it.slug == slug && it.episode == episode) }
    }

    suspend fun markAllWatched(slug: String, episodes: List<Int>) {
        if (episodes.isEmpty()) return
        val now = System.currentTimeMillis()
        episodes.forEach { episode ->
            val previous = _progress.value.firstOrNull { it.slug == slug && it.episode == episode }
            val duration = previous?.durationMs ?: 0L
            _progress.value = _progress.value.map { p ->
                if (p.slug == slug && p.episode == episode) p.copy(
                    positionMs = duration, durationMs = duration,
                    completed = true, updatedAt = now,
                ) else p
            }
            if (_progress.value.none { it.slug == slug && it.episode == episode }) {
                _progress.value += ProgressEntity(
                    slug = slug, episode = episode,
                    positionMs = duration, durationMs = duration,
                    completed = true, updatedAt = now,
                )
            }
        }
    }

    suspend fun unmarkAllWatched(slug: String, episodes: List<Int>) {
        if (episodes.isEmpty()) return
        _progress.value = _progress.value.filter { !(it.slug == slug && episodes.contains(it.episode)) }
    }

    suspend fun saveProgress(slug: String, episode: Int, positionMs: Long,
                             durationMs: Long, completed: Boolean) {
        val now = System.currentTimeMillis()
        _progress.value = _progress.value.map { p ->
            if (p.slug == slug && p.episode == episode) p.copy(
                positionMs = positionMs, durationMs = durationMs,
                completed = completed, updatedAt = now,
            ) else p
        }
        if (_progress.value.none { it.slug == slug && it.episode == episode }) {
            _progress.value += ProgressEntity(
                slug = slug, episode = episode,
                positionMs = positionMs, durationMs = durationMs,
                completed = completed, updatedAt = now,
            )
        }
    }

    suspend fun episodeProgress(slug: String, episode: Int): ProgressEntity? =
        _progress.value.firstOrNull { it.slug == slug && it.episode == episode }

    fun observeProgressFor(slug: String): Flow<List<ProgressEntity>> =
        flowOf(_progress.value.filter { it.slug == slug })

    // Credenciales de sincronización
    suspend fun getCredentials(): SyncCredentialsEntity? = _credentials.value

    suspend fun setCredentials(code: String, token: String) {
        _credentials.value = SyncCredentialsEntity(id = 1, code = code, token = token)
    }

    suspend fun clearCredentials() {
        _credentials.value = null
    }

    // Acceso a todas las entidades para sync
    suspend fun getAllFavorites(): List<FavoriteEntity> = _favorites.value.toList()
    suspend fun getAllProgress(): List<ProgressEntity> = _progress.value.toList()
}

/**
 * Favoritos del usuario móvil. PK = slug (un favorito por anime).
 */
data class FavoriteEntity(
    val slug: String,
    val title: String,
    val animeId: Int = 0,
    val posterUrl: String? = null,
    val addedAt: Long = 0,
    val lastWatchedEpisode: Int? = null,
    val lastAiredEpisode: Int? = null,
    val totalEpisodes: Int? = null,
    val statusLabel: String? = null,
    val syncedAt: Long = 0,
    val updatedAt: Long = 0,
    val deletedAt: Long? = null,
) {
    val pendingEpisodes: Int?
        get() {
            val aired = lastAiredEpisode ?: return null
            val seen = lastWatchedEpisode ?: 0
            return (aired - seen).coerceAtLeast(0)
        }
}

/**
 * Progreso de reproducción por episodio. PK = (slug, episode).
 */
data class ProgressEntity(
    val slug: String,
    val episode: Int,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val completed: Boolean = false,
    val updatedAt: Long = 0,
) {
    val fraction: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * Credenciales de sincronización guardadas localmente.
 */
data class SyncCredentialsEntity(
    val id: Int = 1,
    val code: String,
    val token: String,
)
