package com.macastil.tv.catalog.parser

import com.macastil.tv.model.AnimeSearchHit
import com.macastil.tv.network.JkHttpClient

/**
 * Parser del buscador del sitio (`GET /buscar/{consulta}`).
 *
 * Cada resultado es:
 *
 * ```
 * div.anime__item
 *   a[href]                        -> /slug/
 *   div.anime__item__pic[data-setbg] -> póster vertical
 *   div.anime__item__text ul li      -> "Concluido" (estado)
 *   div.anime__item__text ul li.anime-> "Serie"    (tipo)
 *   h5 a[href]                     -> título
 * ```
 *
 * El sitio devuelve como máximo 30 fichas y no pagina.
 */
object SearchParser {

    fun parse(html: String): List<AnimeSearchHit> {
        val doc = JkDom.document(html)

        return doc.select("div.anime__item")
            .mapNotNull { item ->
                val anchor = item.selectFirst("h5 a[href]") ?: item.selectFirst("a[href]")
                val href = anchor?.absUrl("href")?.ifBlank { null }
                    ?: anchor?.attr("href")
                    ?: return@mapNotNull null
                val slug = JkDom.parseAnimePath(href)?.first ?: return@mapNotNull null

                val title = item.selectFirst("h5 a")?.text()?.trim()
                    ?.ifBlank { null }
                    ?: slug

                val poster = item.selectFirst("div.anime__item__pic")
                    ?.attr("data-setbg")
                    ?.trim()
                    ?.ifBlank { null }
                    ?.let { JkHttpClient.absolute(it) }

                // El `li.anime` es el tipo; el otro `li` es el estado.
                val type = item.selectFirst("ul li.anime")?.text()?.trim()?.ifBlank { null }
                val status = item.select("ul li")
                    .firstOrNull { !it.hasClass("anime") }
                    ?.text()
                    ?.trim()
                    ?.ifBlank { null }

                AnimeSearchHit(
                    slug = slug,
                    title = title,
                    posterUrl = poster,
                    type = type,
                    statusLabel = status,
                )
            }
            .distinctBy { it.slug }
    }
}
