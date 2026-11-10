package net.jkanime.tv.catalog.parser

import java.util.Base64

/**
 * Extrae las URLs de stream (`.m3u8`) del HTML de un embed de jkplayer
 * (`jkplayer/um` = Desu, `jkplayer/umv` = Magi).
 *
 * El embed monta un DPlayer con hls.js y la URL aparece de dos formas:
 *
 * ```js
 * // 1. Bloque comentado (versión vieja del player): literal
 * url: 'https://host/....m3u8?st=...&e=...',
 *
 * // 2. Bloque ACTIVO: codificada en base64 para esconder el CDN
 * url: atob('aHR0cHM6Ly9...'),
 * // y además la misma URL en claro dentro de hls.loadSource('...')
 * ```
 *
 * Por eso se devuelven **todos** los candidatos en orden inverso (el bloque
 * activo suele ir después del comentado) y quien los consuma valida cuál
 * responde de verdad con un `#EXTM3U` antes de reproducirlo.
 */
object EmbedStreamParser {

    private val ATOB = Regex("""atob\(\s*'([A-Za-z0-9+/=]+)'\s*\)""")

    private val LITERAL = Regex("""['"](https?://[^'"\s\\]+\.m3u8[^'"\s\\]*)['"]""")

    /** Candidatos de stream, del más probable al menos probable. */
    fun candidates(html: String): List<String> {
        val found = mutableListOf<Pair<Int, String>>()

        // Se ordenan por posición en el documento: el DPlayer activo es el
        // último que aparece (el bloque comentado va antes), así que el orden
        // inverso deja primero lo que hay que probar.
        LITERAL.findAll(html).forEach { match ->
            found += match.range.first to match.groupValues[1]
        }

        ATOB.findAll(html).forEach { match ->
            val decoded = decodeBase64(match.groupValues[1]) ?: return@forEach
            if (decoded.startsWith("http") && decoded.contains(".m3u8")) {
                found += match.range.first to decoded
            }
        }

        return found
            .sortedByDescending { it.first }
            .map { it.second }
            .distinct()
    }

    private fun decodeBase64(value: String): String? = runCatching {
        String(Base64.getDecoder().decode(value))
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
