package com.macastil.mobile.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Biblioteca del usuario móvil: favoritos, progreso y credenciales de sync.
 *
 * Persiste en `SharedPreferences` con JSON. Se eligió esto en lugar de Room
 * porque en `:app-mobile` KSP fallaba al generar las implementaciones
 * (`java.lang.IllegalStateException` sin mensaje) y el volumen de datos aquí
 * es pequeño (decenas de filas). La app TV sigue con Room.
 */
class LibraryRepository(
    context: Context? = null,
) {
    private val prefs: SharedPreferences? =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _favorites = MutableStateFlow(loadFavorites())
    val favorites: Flow<List<FavoriteEntity>> = _favorites.asStateFlow()

    private val _progress = MutableStateFlow(loadProgress())
    val progress: Flow<List<ProgressEntity>> = _progress.asStateFlow()

    private val _credentials = MutableStateFlow(loadCredentials())
    val credentials: Flow<SyncCredentialsEntity?> = _credentials.asStateFlow()

    // ---------------------------------------------------------------- lectura

    suspend fun isFavorite(slug: String): Boolean =
        _favorites.value.any { it.slug == slug && it.deletedAt == null }

    suspend fun getFavorite(slug: String): FavoriteEntity? =
        _favorites.value.firstOrNull { it.slug == slug && it.deletedAt == null }

    suspend fun episodeProgress(slug: String, episode: Int): ProgressEntity? =
        _progress.value.firstOrNull { it.slug == slug && it.episode == episode }

    fun observeProgressFor(slug: String): Flow<List<ProgressEntity>> =
        _progress.map { list -> list.filter { it.slug == slug } }

    /** Último episodio empezado sin terminar: de ahí sale "Continuar". */
    suspend fun resumeEpisode(slug: String): ProgressEntity? =
        _progress.value
            .filter { it.slug == slug && !it.completed && it.positionMs > 0 }
            .maxByOrNull { it.updatedAt }

    // -------------------------------------------------------------- escritura

    suspend fun toggleFavorite(
        slug: String,
        title: String,
        posterUrl: String?,
        animeId: Int = 0,
        statusLabel: String? = null,
        lastAiredEpisode: Int? = null,
        totalEpisodes: Int? = null,
    ): Boolean {
        val now = System.currentTimeMillis()
        val existing = _favorites.value.firstOrNull { it.slug == slug }
        val removed = existing != null && existing.deletedAt == null

        _favorites.value = if (removed) {
            _favorites.value.map {
                if (it.slug == slug) it.copy(deletedAt = now, updatedAt = now) else it
            }
        } else {
            _favorites.value.filter { it.slug != slug } + FavoriteEntity(
                slug = slug,
                title = title,
                animeId = animeId,
                posterUrl = posterUrl ?: existing?.posterUrl,
                addedAt = existing?.addedAt ?: now,
                lastWatchedEpisode = existing?.lastWatchedEpisode,
                lastAiredEpisode = lastAiredEpisode ?: existing?.lastAiredEpisode,
                totalEpisodes = totalEpisodes ?: existing?.totalEpisodes,
                statusLabel = statusLabel ?: existing?.statusLabel,
                syncedAt = existing?.syncedAt ?: now,
                updatedAt = now,
            )
        }
        persistFavorites()
        return !removed
    }

    suspend fun markWatched(slug: String, episode: Int) {
        val current = _progress.value.firstOrNull { it.slug == slug && it.episode == episode }
        val duration = current?.durationMs ?: 0L
        putProgress(slug, episode, duration, duration, completed = true)
        bumpLastWatched(slug, episode)
    }

    suspend fun unmarkWatched(slug: String, episode: Int) {
        _progress.value = _progress.value
            .filterNot { it.slug == slug && it.episode == episode }
        persistProgress()
    }

    suspend fun markAllWatched(slug: String, episodes: List<Int>) {
        if (episodes.isEmpty()) return
        episodes.forEach { episode ->
            val current = _progress.value.firstOrNull { it.slug == slug && it.episode == episode }
            val duration = current?.durationMs ?: 0L
            putProgress(slug, episode, duration, duration, completed = true, persist = false)
        }
        bumpLastWatched(slug, episodes.max())
        persistProgress()
    }

    suspend fun unmarkAllWatched(slug: String, episodes: List<Int>) {
        if (episodes.isEmpty()) return
        _progress.value = _progress.value
            .filterNot { it.slug == slug && episodes.contains(it.episode) }
        persistProgress()
    }

    suspend fun saveProgress(
        slug: String,
        episode: Int,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean,
    ) {
        putProgress(slug, episode, positionMs, durationMs, completed)
        if (completed) bumpLastWatched(slug, episode)
    }

    private suspend fun putProgress(
        slug: String,
        episode: Int,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean,
        persist: Boolean = true,
    ) {
        val now = System.currentTimeMillis()
        val exists = _progress.value.any { it.slug == slug && it.episode == episode }
        _progress.value = if (exists) {
            _progress.value.map {
                if (it.slug == slug && it.episode == episode) {
                    it.copy(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        completed = completed,
                        updatedAt = now,
                    )
                } else {
                    it
                }
            }
        } else {
            _progress.value + ProgressEntity(
                slug = slug,
                episode = episode,
                positionMs = positionMs,
                durationMs = durationMs,
                completed = completed,
                updatedAt = now,
            )
        }
        if (persist) persistProgress()
    }

    /** `MAX(...)` para no bajar el contador si se re-marca un capítulo viejo. */
    private fun bumpLastWatched(slug: String, episode: Int) {
        _favorites.value = _favorites.value.map { fav ->
            if (fav.slug != slug) return@map fav
            val previous = fav.lastWatchedEpisode ?: 0
            if (episode <= previous) fav else fav.copy(
                lastWatchedEpisode = episode,
                updatedAt = System.currentTimeMillis(),
            )
        }
        persistFavorites()
    }

    // ------------------------------------------------------- credenciales sync

    suspend fun getCredentials(): SyncCredentialsEntity? = _credentials.value

    suspend fun setCredentials(code: String, token: String) {
        val value = SyncCredentialsEntity(id = 1, code = code, token = token)
        _credentials.value = value
        prefs?.edit()
            ?.putString(KEY_CREDENTIALS, json.encodeToString(SyncCredentialsEntity.serializer(), value))
            ?.apply()
    }

    suspend fun clearCredentials() {
        _credentials.value = null
        prefs?.edit()?.remove(KEY_CREDENTIALS)?.apply()
    }

    // --------------------------------------------------------- acceso al sync

    suspend fun getAllFavorites(): List<FavoriteEntity> = _favorites.value.toList()
    suspend fun getAllProgress(): List<ProgressEntity> = _progress.value.toList()

    /** Sustituye la biblioteca local con lo que venga de la nube (merge ya hecho). */
    suspend fun replaceFromRemote(favorites: List<FavoriteEntity>, progress: List<ProgressEntity>) {
        _favorites.value = favorites
        _progress.value = progress
        persistFavorites()
        persistProgress()
    }

    /** Merge last-write-wins de un favorito remoto (respeta tombstones). */
    suspend fun upsertFavorite(remote: FavoriteEntity) {
        val existing = _favorites.value.firstOrNull { it.slug == remote.slug }
        if (existing == null || remote.updatedAt > existing.updatedAt) {
            _favorites.value = _favorites.value.filterNot { it.slug == remote.slug } + remote
            persistFavorites()
        }
    }

    /** Merge last-write-wins de un progreso remoto. */
    suspend fun upsertProgress(remote: ProgressEntity) {
        val existing = _progress.value.firstOrNull {
            it.slug == remote.slug && it.episode == remote.episode
        }
        if (existing == null || remote.updatedAt > existing.updatedAt) {
            _progress.value = _progress.value.filterNot {
                it.slug == remote.slug && it.episode == remote.episode
            } + remote
            persistProgress()
        }
    }

    // ------------------------------------------------------------ persistencia

    private fun persistFavorites() {
        prefs?.edit()?.putString(
            KEY_FAVORITES,
            json.encodeToString(ListSerializer(FavoriteEntity.serializer()), _favorites.value),
        )?.apply()
    }

    private fun persistProgress() {
        prefs?.edit()?.putString(
            KEY_PROGRESS,
            json.encodeToString(ListSerializer(ProgressEntity.serializer()), _progress.value),
        )?.apply()
    }

    private fun loadFavorites(): List<FavoriteEntity> =
        read(KEY_FAVORITES, ListSerializer(FavoriteEntity.serializer()))

    private fun loadProgress(): List<ProgressEntity> =
        read(KEY_PROGRESS, ListSerializer(ProgressEntity.serializer()))

    private fun loadCredentials(): SyncCredentialsEntity? {
        val raw = prefs?.getString(KEY_CREDENTIALS, null) ?: return null
        return runCatching { json.decodeFromString(SyncCredentialsEntity.serializer(), raw) }.getOrNull()
    }

    private fun <T> read(key: String, serializer: kotlinx.serialization.KSerializer<List<T>>): List<T> {
        val raw = prefs?.getString(key, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrElse { emptyList() }
    }

    private companion object {
        const val PREFS_NAME = "jk-mobile-library"
        const val KEY_FAVORITES = "favorites"
        const val KEY_PROGRESS = "progress"
        const val KEY_CREDENTIALS = "sync-credentials"
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/**
 * Favorito del usuario móvil. PK = slug (un favorito por anime).
 */
@Serializable
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
@Serializable
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
@Serializable
data class SyncCredentialsEntity(
    val id: Int = 1,
    val code: String,
    val token: String,
)
