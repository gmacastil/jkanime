package net.jkanime.tv.catalog.parser

import net.jkanime.tv.model.AiringStatus
import net.jkanime.tv.model.AnimeDetail
import net.jkanime.tv.network.JkParseException
import org.jsoup.nodes.Element

/**
 * Parser de la página de detalle `/slug/`.
 *
 * Datos que se leen:
 *  - `[data-anime]`                      -> id interno del anime (clave para /ajax/episodes)
 *  - `.anime__details__content h3`       -> título
 *  - `h3 + span`                         -> título alterno
 *  - `p.scroll`                          -> sinopsis
 *  - `.anime_pic img` / `og:image`       -> póster
 *  - `.anime__details__widget li`        -> tipo, géneros, studios, estado, etc.
 *  - `a#uep`                             -> último episodio
 *  - `#proxep`                           -> próximo episodio (si sigue en emisión)
 */
object AnimeDetailParser {

    fun parse(html: String, slug: String): AnimeDetail {
        val doc = JkDom.document(html)
        val root = doc.selectFirst(".anime__details__content") ?: doc

        val animeId = doc.selectFirst("[data-anime]")
            ?.attr("data-anime")
            ?.toIntOrNull()
            ?: throw JkParseException("No se encontró data-anime en $slug")

        val titleElement = root.selectFirst("h3")
        val title = titleElement?.text()?.trim()?.ifBlank { null }
            ?: JkDom.metaContent(doc, "og:title")
            ?: slug

        val titleAlt = titleElement?.nextElementSibling()
            ?.takeIf { it.tagName() == "span" }
            ?.text()
            ?.trim()
            ?.ifBlank { null }

        val synopsis = root.selectFirst("p.scroll")?.text()?.trim()?.ifBlank { null }

        val poster = root.selectFirst(".anime_pic img")
            ?.absUrl("src")
            ?.ifBlank { null }
            ?: JkDom.metaContent(doc, "og:image")

        // El bloque de metadatos vive en `.anime_data`; `.anime__details__widget`
        // es la zona de pestañas (personajes/reseñas) y viene vacío.
        val widget = root.selectFirst(".anime_data") ?: root

        val nextEpisode = doc.select("#proxep")
            .map { it.text().trim() }
            .firstOrNull { it.contains("róximo", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?.ifBlank { null }

        val lastEpisodeAnchor = doc.selectFirst("a#uep")
        val lastEpisodeUrl = lastEpisodeAnchor?.absUrl("href")?.ifBlank { null }
        val lastEpisodeNumber = lastEpisodeUrl?.let { JkDom.parseAnimePath(it)?.second }

        return AnimeDetail(
            animeId = animeId,
            slug = slug,
            title = title,
            titleAlt = titleAlt,
            synopsis = synopsis,
            posterUrl = poster,
            type = JkDom.liValue(JkDom.liByLabel(widget, "Tipo")),
            status = AiringStatus.fromRaw(doc.selectFirst("[data-status]")?.attr("data-status")),
            statusLabel = JkDom.liValue(JkDom.liByLabel(widget, "Estado")),
            genres = links(widget, "Generos"),
            studios = links(widget, "Studios"),
            demographics = links(widget, "Demografia"),
            languages = links(widget, "Idiomas"),
            totalEpisodes = JkDom.liValue(JkDom.liByLabel(widget, "Episodios"))
                ?.filter { it.isDigit() }
                ?.toIntOrNull(),
            duration = JkDom.liValue(JkDom.liByLabel(widget, "Duracion")),
            airedOn = JkDom.liValue(JkDom.liByLabel(widget, "Emitido")),
            quality = JkDom.liValue(JkDom.liByLabel(widget, "Calidad")),
            nextEpisodeLabel = nextEpisode,
            lastEpisodeNumber = lastEpisodeNumber,
            lastEpisodeUrl = lastEpisodeUrl,
        )
    }

    private fun links(widget: Element, label: String): List<String> =
        JkDom.liByLabel(widget, label)
            ?.select("a")
            ?.map { it.text().trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
}
