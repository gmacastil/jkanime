package com.macastil.tv.catalog

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.macastil.tv.model.AnimeCard
import com.macastil.tv.model.AnimeDetail
import com.macastil.tv.model.AnimeSearchHit
import com.macastil.tv.model.DirectorioPage
import com.macastil.tv.model.EpisodesPage
import com.macastil.tv.model.ProgramacionSection
import com.macastil.tv.model.VideoServer

/**
 * Caché en memoria con TTL + "fail-open": si la red falla pero hay datos
 * previos, se devuelven los previos en lugar de romper la pantalla.
 *
 * La persistencia (Room) llega en la fase de favoritos; esto solo evita
 * machacar el sitio cada vez que el usuario vuelve al home.
 */
class JkanimeRepository(
    private val dataSource: JkanimeCatalogDataSource = JkanimeCatalogDataSource(),
) {

    private data class Entry<T>(val value: T, val storedAt: Long)

    private val mutex = Mutex()

    private var programacionCache: Entry<Map<ProgramacionSection, List<AnimeCard>>>? = null
    private val detailCache = mutableMapOf<String, Entry<AnimeDetail>>()
    private val episodesCache = mutableMapOf<Int, Entry<EpisodesPage>>()
    private val serversCache = mutableMapOf<String, Entry<List<VideoServer>>>()
    private val searchCache = mutableMapOf<String, Entry<List<AnimeSearchHit>>>()
    private val directorioCache = mutableMapOf<String, Entry<DirectorioPage>>()

    suspend fun programacion(force: Boolean = false): Map<ProgramacionSection, List<AnimeCard>> =
        mutex.withLock {
            val cached = programacionCache
            if (!force && cached != null && isFresh(cached.storedAt, PROGRAMACION_TTL_MS)) {
                return@withLock cached.value
            }
            val loaded = runCatching { dataSource.loadProgramacion() }
                .getOrElse { error -> cached?.value ?: throw error }
            programacionCache = Entry(loaded, now())
            loaded
        }

    suspend fun detail(slug: String, force: Boolean = false): AnimeDetail = mutex.withLock {
        val cached = detailCache[slug]
        if (!force && cached != null && isFresh(cached.storedAt, DETAIL_TTL_MS)) {
            return@withLock cached.value
        }
        val loaded = runCatching { dataSource.loadDetail(slug) }
            .getOrElse { error -> cached?.value ?: throw error }
        detailCache[slug] = Entry(loaded, now())
        loaded
    }

    suspend fun episodes(
        animeId: Int,
        slug: String,
        page: Int = 1,
        force: Boolean = false,
    ): EpisodesPage = mutex.withLock {
        val key = animeId * 1000 + page
        val cached = episodesCache[key]
        if (!force && cached != null && isFresh(cached.storedAt, EPISODES_TTL_MS)) {
            return@withLock cached.value
        }
        val loaded = runCatching { dataSource.loadEpisodes(animeId, slug, page) }
            .getOrElse { error -> cached?.value ?: throw error }
        episodesCache[key] = Entry(loaded, now())
        loaded
    }

    suspend fun servers(episodeUrl: String, force: Boolean = false): List<VideoServer> =
        mutex.withLock {
            val cached = serversCache[episodeUrl]
            if (!force && cached != null && isFresh(cached.storedAt, SERVERS_TTL_MS)) {
                return@withLock cached.value
            }
            val loaded = runCatching { dataSource.loadServers(episodeUrl) }
                .getOrElse { error -> cached?.value ?: throw error }
            serversCache[episodeUrl] = Entry(loaded, now())
            loaded
        }

    /**
     * URL directa de reproducción (m3u8) de un embed de jkplayer, o `null`
     * si el embed no la expone (fallback al WebView).
     *
     * Sin caché a propósito: el CDN firma el manifest con un token con
     * caducidad (`&e=<unix>`), así que una URL guardada puede morir entre
     * sesiones. La resolución cuesta ~1 petición al abrir el capítulo.
     */
    suspend fun stream(embedUrl: String, referer: String): String? =
        dataSource.loadStream(embedUrl, referer = referer)

    /**
     * Buscador del sitio. La clave de caché va en minúsculas para que
     * "Naruto" y "naruto" no gasten dos peticiones (el sitio es
     * case-insensitive).
     */
    suspend fun search(query: String, force: Boolean = false): List<AnimeSearchHit> =
        mutex.withLock {
            val key = query.trim().lowercase()
            if (key.isBlank()) return@withLock emptyList()

            val cached = searchCache[key]
            if (!force && cached != null && isFresh(cached.storedAt, SEARCH_TTL_MS)) {
                return@withLock cached.value
            }
            val loaded = runCatching { dataSource.loadSearch(key) }
                .getOrElse { error -> cached?.value ?: throw error }
            searchCache[key] = Entry(loaded, now())
            loaded
        }

    /**
     * Directorio con filtros. La clave incluye filtros y página para que cada
     * combinación tenga su propia entrada; el usuario cambia filtros seguido y
     * volver a un estado anterior no debe costar otra petición.
     */
    suspend fun directorio(
        filters: Map<String, String> = emptyMap(),
        page: Int = 1,
        force: Boolean = false,
    ): DirectorioPage = mutex.withLock {
        val key = filters.filterValues { it.isNotBlank() }
            .toSortedMap()
            .entries
            .joinToString("&") { "${'$'}{it.key}=${'$'}{it.value}" } + "#p$page"

        val cached = directorioCache[key]
        if (!force && cached != null && isFresh(cached.storedAt, DIRECTORIO_TTL_MS)) {
            return@withLock cached.value
        }
        val loaded = runCatching { dataSource.loadDirectorio(filters, page) }
            .getOrElse { error -> cached?.value ?: throw error }
        directorioCache[key] = Entry(loaded, now())
        loaded
    }

    suspend fun invalidateAll() = mutex.withLock {
        programacionCache = null
        detailCache.clear()
        episodesCache.clear()
        serversCache.clear()
        searchCache.clear()
        directorioCache.clear()
        dataSource.resetSession()
    }

    private fun now() = System.currentTimeMillis()

    private fun isFresh(storedAt: Long, ttlMs: Long) = now() - storedAt < ttlMs

    companion object {
        const val PROGRAMACION_TTL_MS = 10 * 60 * 1000L
        const val DETAIL_TTL_MS = 24 * 60 * 60 * 1000L
        const val EPISODES_TTL_MS = 30 * 60 * 1000L
        const val SERVERS_TTL_MS = 30 * 60 * 1000L
        const val SEARCH_TTL_MS = 10 * 60 * 1000L
        const val DIRECTORIO_TTL_MS = 10 * 60 * 1000L
    }
}
