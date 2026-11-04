package net.jkanime.tv.catalog

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.jkanime.tv.model.AnimeCard
import net.jkanime.tv.model.AnimeDetail
import net.jkanime.tv.model.EpisodesPage
import net.jkanime.tv.model.ProgramacionSection
import net.jkanime.tv.model.VideoServer

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

    suspend fun invalidateAll() = mutex.withLock {
        programacionCache = null
        detailCache.clear()
        episodesCache.clear()
        serversCache.clear()
        dataSource.resetSession()
    }

    private fun now() = System.currentTimeMillis()

    private fun isFresh(storedAt: Long, ttlMs: Long) = now() - storedAt < ttlMs

    companion object {
        const val PROGRAMACION_TTL_MS = 10 * 60 * 1000L
        const val DETAIL_TTL_MS = 24 * 60 * 60 * 1000L
        const val EPISODES_TTL_MS = 30 * 60 * 1000L
        const val SERVERS_TTL_MS = 30 * 60 * 1000L
    }
}
