package net.jkanime.tv.catalog

import net.jkanime.tv.catalog.parser.AnimeDetailParser
import net.jkanime.tv.catalog.parser.DirectorioParser
import net.jkanime.tv.catalog.parser.EmbedStreamParser
import net.jkanime.tv.catalog.parser.EpisodeJsonParser
import net.jkanime.tv.catalog.parser.ProgramacionParser
import net.jkanime.tv.catalog.parser.SearchParser
import net.jkanime.tv.catalog.parser.VideoServerParser
import net.jkanime.tv.model.AnimeCard
import net.jkanime.tv.model.AnimeDetail
import net.jkanime.tv.model.AnimeSearchHit
import net.jkanime.tv.model.DirectorioPage
import net.jkanime.tv.model.EpisodesPage
import net.jkanime.tv.model.Jk
import net.jkanime.tv.model.ProgramacionSection
import net.jkanime.tv.model.VideoServer
import net.jkanime.tv.network.JkHttpClient

/**
 * Única puerta de entrada al sitio. Todo el conocimiento del DOM vive en
 * `catalog.parser`; aquí solo se orquesta red + parsers.
 */
class JkanimeCatalogDataSource(private val http: JkHttpClient = JkHttpClient()) {

    /** Home: las 3 listas de "Programación" que usamos. */
    suspend fun loadProgramacion(): Map<ProgramacionSection, List<AnimeCard>> =
        ProgramacionParser.parse(http.getHtml(Jk.HOME_URL))

    suspend fun loadDetail(slug: String): AnimeDetail {
        val url = Jk.animeUrl(slug)
        return AnimeDetailParser.parse(http.getHtml(url), slug)
    }

    /**
     * El endpoint exige sesión + CSRF. Se pide la página del anime primero para
     * dejar la cookie y obtener el token, y luego se hace el POST.
     */
    suspend fun loadEpisodes(
        animeId: Int,
        slug: String,
        page: Int = 1,
    ): EpisodesPage {
        val referer = Jk.animeUrl(slug)
        val token = http.csrfToken(referer)
        val url = buildString {
            append(Jk.episodesAjaxUrl(animeId))
            if (page > 1) append("?page=").append(page)
        }

        val body = http.postForm(
            url = url,
            form = mapOf("_token" to token),
            referer = referer,
            extraHeaders = mapOf(
                "X-CSRF-TOKEN" to token,
                "X-Requested-With" to "XMLHttpRequest",
            ),
        )
        return EpisodeJsonParser.parse(body, animeId, slug)
    }

    suspend fun loadServers(episodeUrl: String, referer: String? = null): List<VideoServer> =
        VideoServerParser.parse(http.getHtml(episodeUrl, referer = referer), episodeUrl)

    /**
     * Resuelve el `m3u8` real de un embed de jkplayer (Desu/Magi): el HTML del
     * embed trae la URL del CDN codificada y hay que validarla contra el
     * servidor antes de darla por buena.
     *
     * Devuelve `null` cuando el embed no expone stream directo (embeds de
     * terceros con reproductor JS ofuscado): en ese caso el caller cae al
     * WebView, que sigue siendo el fallback.
     */
    suspend fun loadStream(embedUrl: String, referer: String? = null): String? {
        val html = runCatching { http.getHtml(embedUrl, referer = referer) }.getOrNull()
            ?: return null

        for (candidate in EmbedStreamParser.candidates(html)) {
            val manifest = runCatching {
                http.getText(candidate, referer = referer)
            }.getOrNull() ?: continue
            if (manifest.contains("#EXTM3U")) return candidate
        }
        return null
    }

    /** Buscador del sitio: HTML server-rendered con hasta 30 fichas. */
    suspend fun loadSearch(query: String): List<AnimeSearchHit> =
        SearchParser.parse(http.getHtml(Jk.searchUrl(query)))

    /** Directorio con filtros: 30 fichas por página + el formulario de filtros. */
    suspend fun loadDirectorio(filters: Map<String, String>, page: Int): DirectorioPage =
        DirectorioParser.parse(http.getHtml(Jk.directorioUrl(filters, page)))

    suspend fun resetSession() = http.resetSession()
}
