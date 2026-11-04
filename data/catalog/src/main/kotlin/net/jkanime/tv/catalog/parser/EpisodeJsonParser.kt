package net.jkanime.tv.catalog.parser

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import net.jkanime.tv.model.Episode
import net.jkanime.tv.model.EpisodesPage
import net.jkanime.tv.model.Jk
import net.jkanime.tv.network.JkParseException

/**
 * Parser del JSON que devuelve `POST /ajax/episodes/{animeId}/`.
 *
 * Shape real (verificado):
 * ```json
 * {"current_page":1,"last_page":1,"total":5,
 *  "data":[{"id":78383,"number":1,"title":"... - 1",
 *           "image":"jkvideo_xxx.jpg","timestamp":"2026-10-04 02:28:44"}]}
 * ```
 * `number` puede venir como entero o decimal (episodios 12.5).
 */
object EpisodeJsonParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(raw: String, animeId: Int, slug: String): EpisodesPage {
        val dto = runCatching { json.decodeFromString<EpisodesDto>(raw) }
            .getOrElse { throw JkParseException("JSON de episodios inválido para $slug") }

        val episodes = dto.data.mapNotNull { item ->
            val number = runCatching { item.number.jsonPrimitive.double }.getOrNull()
                ?: return@mapNotNull null

            Episode(
                id = item.id,
                animeId = animeId,
                number = number,
                title = item.title.ifBlank { "$slug - $number" },
                thumbUrl = item.image?.takeIf { it.isNotBlank() }?.let { Jk.CDN_VIDEO + it },
                airedAt = item.timestamp,
                url = Jk.episodeUrl(slug, number.toInt()),
            )
        }

        return EpisodesPage(
            currentPage = dto.currentPage,
            lastPage = dto.lastPage,
            total = dto.total,
            episodes = episodes,
        )
    }

    @Serializable
    private data class EpisodesDto(
        @SerialName("current_page") val currentPage: Int = 1,
        @SerialName("last_page") val lastPage: Int = 1,
        val total: Int = 0,
        val data: List<EpisodeDto> = emptyList(),
    )

    @Serializable
    private data class EpisodeDto(
        val id: Long = 0,
        val number: JsonElement,
        val title: String = "",
        val image: String? = null,
        val timestamp: String? = null,
    )
}
