package com.macastil.tv.catalog.parser

import com.macastil.tv.model.AnimeCard
import com.macastil.tv.model.Jk
import com.macastil.tv.model.ProgramacionSection
import org.jsoup.nodes.Element

/**
 * Parser de la sección "Programación" del home.
 *
 * El sitio renderiza las 4 listas (Animes / Donghuas / OVAs / Hentai) en el HTML
 * inicial dentro de `div.tab-content > div.tab-pane#<anchor>`. Cada tarjeta es:
 *
 * ```
 * div.card
 *   a[href]                  -> /slug/episodio/
 *   img.card-img-top[src]    -> thumbnail 16:9 del episodio
 *   img[data-animepic]       -> póster vertical del anime
 *   span.badge-primary       -> "Ep 5"
 *   span.badge-secondary     -> "Hoy" | "Ayer" | "Lunes 02"
 *   h5.card-title            -> título
 * ```
 */
object ProgramacionParser {

    fun parse(html: String): Map<ProgramacionSection, List<AnimeCard>> {
        val doc = JkDom.document(html)
        val result = LinkedHashMap<ProgramacionSection, List<AnimeCard>>()

        for (section in ProgramacionSection.entries) {
            val pane = doc.selectFirst("div.tab-pane#${section.anchor}")
                ?: doc.selectFirst("#${section.anchor}")
            val cards = pane?.select("div.card").orEmpty()
                .mapNotNull { parseCard(it, section) }
                .distinctBy { it.episodeUrl }
            result[section] = cards
        }
        return result
    }

    private fun parseCard(card: Element, section: ProgramacionSection): AnimeCard? {
        val anchor = card.selectFirst("a[href]") ?: return null
        val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
        val (slug, episodeNumber) = JkDom.parseAnimePath(href) ?: return null

        val img = card.selectFirst("img")
        val thumb = card.selectFirst("img.card-img-top")
            ?.absUrl("src")
            ?.ifBlank { null }
            ?: img?.absUrl("src")?.ifBlank { null }

        val poster = card.selectFirst("img[data-animepic]")
            ?.attr("data-animepic")
            ?.trim()
            ?.ifBlank { null }

        val title = card.selectFirst("h5.card-title")?.text()?.trim()
            ?.ifBlank { null }
            ?: img?.attr("alt")?.substringBeforeLast(" - ")?.trim()
            ?: slug

        val badges = card.select("span.badge").map { it.text().trim() }
        val epBadge = badges.firstOrNull { it.startsWith("Ep", ignoreCase = true) }
        val epFromBadge = epBadge?.removePrefix("Ep")?.removePrefix("ep")?.trim()?.toIntOrNull()
        val dateLabel = badges.firstOrNull {
            it.isNotBlank() && !it.startsWith("Ep", ignoreCase = true)
        }

        val episodeUrl = if (episodeNumber != null) {
            Jk.episodeUrl(slug, episodeNumber)
        } else {
            href
        }

        return AnimeCard(
            slug = slug,
            title = title,
            episodeNumber = episodeNumber ?: epFromBadge,
            episodeUrl = episodeUrl,
            thumbUrl = thumb,
            posterUrl = poster,
            dateLabel = dateLabel,
            section = section,
        )
    }
}
