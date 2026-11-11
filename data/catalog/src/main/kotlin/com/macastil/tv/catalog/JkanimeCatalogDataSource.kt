package com.macastil.tv.catalog

import com.macastil.tv.catalog.parser.AnimeDetailParser
import com.macastil.tv.catalog.parser.DirectorioParser
import com.macastil.tv.catalog.parser.EmbedStreamParser
import com.macastil.tv.catalog.parser.EpisodeJsonParser
import com.macastil.tv.catalog.parser.ProgramacionParser
import com.macastil.tv.catalog.parser.SearchParser
import com.macastil.tv.catalog.parser.VideoServerParser
import com.macastil.tv.model.AnimeCard
import com.macastil.tv.model.AnimeDetail
import com.macastil.tv.model.AnimeSearchHit
import com.macastil.tv.model.DirectorioPage
import com.macastil.tv.model.EpisodesPage
import com.macastil.tv.model.Jk
import com.macastil.tv.model.ProgramacionSection
import com.macastil.tv.model.VideoServer
import com.macastil.tv.network.JkHttpClient

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
     *
     * **Ojo con la página:** va como segmento de ruta
     * (`/ajax/episodes/{id}/{pagina}`), igual que lo hace el JS del sitio. Con
     * `?page=` o `?p=` el servidor ignora el valor y devuelve siempre la
     * primera página, así que "Cargar más" repetía el mismo bloque.
     */
    suspend fun loadEpisodes(
        animeId: Int,
        slug: String,
        page: Int = 1,
    ): EpisodesPage {
        val referer = Jk.animeUrl(slug)
        val token = http.csrfToken(referer)
        val url = Jk.episodesAjaxUrl(animeId, page)

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
