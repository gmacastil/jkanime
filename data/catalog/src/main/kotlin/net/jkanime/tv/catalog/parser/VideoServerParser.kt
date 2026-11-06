package net.jkanime.tv.catalog.parser

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.jkanime.tv.model.ServerKind
import net.jkanime.tv.model.VideoServer
import java.util.Base64

/**
 * Parser de las fuentes de video de un episodio.
 *
 * La página `/{slug}/{ep}/` incluye:
 * ```js
 * var servers = [{"remote":"<base64>","slug":"...","server":"Streamwish","lang":1,"size":"289 MB"}]
 * ```
 * `remote` es base64 de la URL del embed de un tercero (Streamwish, VOE, Filemoon,
 * Vidhide, Mega, Mediafire...). No hay MP4 ni m3u8 directo: el player real es
 * WebView sobre el embed (ver `:feature:player`).
 */
object VideoServerParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val SERVERS_JSON = Regex(
        """var\s+servers\s*=\s*(\[.*?])\s*;""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    private val JKPLAYER_URL = Regex(
        """https://jkanime\.net/jkplayer/([a-z0-9]+)\?e=[A-Za-z0-9+/=%]+""",
    )

    private val DOWNLOAD_HOSTS = setOf("mega", "mediafire", "1fichier", "zippyshare")

    fun parse(html: String, episodeUrl: String): List<VideoServer> {
        val servers = mutableListOf<VideoServer>()

        SERVERS_JSON.find(html)?.groupValues?.getOrNull(1)?.let { rawArray ->
            runCatching { json.decodeFromString<List<ServerDto>>(rawArray) }
                .getOrDefault(emptyList())
                .forEach { dto ->
                    val url = decodeRemote(dto.remote) ?: return@forEach
                    servers += VideoServer(
                        name = dto.server.ifBlank { "Servidor" },
                        url = url,
                        lang = dto.lang,
                        size = dto.size,
                        kind = kindOf(dto.server),
                    )
                }
        }

        // Player propio del sitio. OJO: los iframes de jkplayer NO existen como
        // elementos del DOM, están dentro de un <script> como strings JS
        // (`video[0] = '<iframe ...>'`), así que se extraen del HTML crudo.
        JKPLAYER_URL.findAll(html).forEach { match ->
            val letter = match.groupValues[1]
            val url = match.value
            if (servers.none { it.url == url }) {
                servers += VideoServer(
                    name = jkPlayerName(letter),
                    url = url,
                    kind = ServerKind.JKPLAYER,
                )
            }
        }

        val distinct = servers.distinctBy { it.url }
        if (distinct.isEmpty()) {
            return listOf(VideoServer(name = "Sitio web", url = episodeUrl, kind = ServerKind.WEB))
        }

        return distinct.sortedBy { server ->
            when (server.kind) {
                ServerKind.STREAM -> 0
                ServerKind.JKPLAYER -> 1
                ServerKind.WEB -> 2
                ServerKind.DOWNLOAD -> 3
            }
        }
    }

    /**
     * Nombre real del player propio del sitio. `jkplayer/um` es **Desu** y
     * `jkplayer/umv` es **Magi** (verificado en varios episodios: son las dos
     * pestañas "Desu" y "Magi" que renderiza la página). Desu es además el
     * player por defecto del propio sitio, y el que usamos nosotros.
     */
    private fun jkPlayerName(letter: String): String = when (letter.lowercase()) {
        "um" -> "Desu"
        "umv" -> "Magi"
        else -> "JKPlayer ${letter.uppercase()}"
    }

    private fun kindOf(serverName: String): ServerKind =
        if (DOWNLOAD_HOSTS.any { serverName.contains(it, ignoreCase = true) }) {
            ServerKind.DOWNLOAD
        } else {
            ServerKind.STREAM
        }

    private fun decodeRemote(remote: String): String? {
        val trimmed = remote.trim()
        if (trimmed.startsWith("http")) return trimmed
        val decoded = runCatching {
            String(Base64.getDecoder().decode(trimmed))
        }.getOrNull()?.trim() ?: return null
        return decoded.takeIf { it.startsWith("http") }
    }

    @Serializable
    private data class ServerDto(
        val remote: String = "",
        val server: String = "",
        val lang: Int = 1,
        val size: String? = null,
        val slug: String? = null,
        val append: Int = 0,
    )
}
