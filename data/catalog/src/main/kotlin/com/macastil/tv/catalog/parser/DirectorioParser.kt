package com.macastil.tv.catalog.parser

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.macastil.tv.model.AnimeSearchHit
import com.macastil.tv.model.DirectorioFilter
import com.macastil.tv.model.DirectorioOption
import com.macastil.tv.model.DirectorioPage

/**
 * Parser del directorio (`GET /directorio`).
 *
 * El HTML trae dos cosas de interés:
 *
 *  1. El **formulario de filtros**: un `<select name="…">` por criterio
 *     (`filtro`, `genero`, `letra`, `demografia`, `categoria`, `tipo`,
 *     `estado`, `fecha`, `temporada`, `orden`).
 *  2. Los **resultados** no están en el DOM: el sitio los inyecta por JS desde
 *     una variable `var animes = {…}` con el JSON paginado de Laravel
 *     (`current_page`, `last_page`, `total`, `data[30]`).
 *
 * Parsear el JSON en vez del DOM es más barato y más estable: el DOM se
 * rellena en el cliente y aquí no hay navegador.
 */
object DirectorioParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Etiquetas del sitio, que publica los `<select>` sin `<label>`. */
    private val LABELS = mapOf(
        "filtro" to "Ordenar por",
        "genero" to "Género",
        "letra" to "Letra",
        "demografia" to "Demografía",
        "categoria" to "Categoría",
        "tipo" to "Tipo",
        "estado" to "Estado",
        "fecha" to "Año",
        "temporada" to "Temporada",
        "orden" to "Dirección",
    )

    fun parse(html: String): DirectorioPage {
        val doc = JkDom.document(html)

        val filters = doc.select("form[action*=directorio] select[name]")
            .mapNotNull { select ->
                val name = select.attr("name").trim()
                if (name.isBlank()) return@mapNotNull null

                val options = select.select("option").mapNotNull { option ->
                    // Ojo: `<option value="">Todos</option>` y `<option>A</option>`
                    // son cosas distintas. `attr("value")` devuelve "" en ambos,
                    // así que hay que mirar si el atributo existe.
                    val value = if (option.hasAttr("value")) option.attr("value") else option.text()
                    DirectorioOption(value = value.trim(), label = option.text().trim())
                }.filter { it.label.isNotBlank() }

                if (options.size < 2) return@mapNotNull null

                DirectorioFilter(
                    name = name,
                    label = LABELS[name] ?: name.replaceFirstChar { it.uppercase() },
                    options = options,
                )
            }

        val dto = extractAnimesJson(html)?.let { raw ->
            runCatching { json.decodeFromString<DirectorioDto>(raw) }.getOrNull()
        }

        val hits = dto?.data.orEmpty().mapNotNull { item ->
            val slug = item.slug.trim()
            if (slug.isBlank()) return@mapNotNull null
            AnimeSearchHit(
                slug = slug,
                title = item.title.trim().ifBlank { slug },
                posterUrl = item.image?.takeIf { it.isNotBlank() },
                type = item.tipo?.takeIf { it.isNotBlank() },
                statusLabel = item.estado?.takeIf { it.isNotBlank() },
            )
        }.distinctBy { it.slug }

        return DirectorioPage(
            filters = filters,
            hits = hits,
            page = dto?.currentPage ?: 1,
            lastPage = dto?.lastPage ?: 1,
            total = dto?.total ?: hits.size,
        )
    }

    /**
     * Extrae el objeto `var animes = {…};` recorriendo llaves con conciencia de
     * cadenas: las sinopsis traen llaves, comillas escapadas y `\r\n`, así que
     * una regex no llega.
     */
    internal fun extractAnimesJson(html: String): String? {
        val marker = Regex("""var\s+animes\s*=\s*""").find(html) ?: return null
        val start = html.indexOf('{', marker.range.last + 1)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (index in start until html.length) {
            val char = html[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return html.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    @Serializable
    private data class DirectorioDto(
        @SerialName("current_page") val currentPage: Int = 1,
        @SerialName("last_page") val lastPage: Int = 1,
        val total: Int = 0,
        val data: List<DirectorioItemDto> = emptyList(),
    )

    @Serializable
    private data class DirectorioItemDto(
        val slug: String = "",
        val title: String = "",
        val image: String? = null,
        val tipo: String? = null,
        val estado: String? = null,
    )
}
