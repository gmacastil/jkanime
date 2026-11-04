package net.jkanime.tv.model

/** Pestañas de la sección "Programación" del home. */
enum class ProgramacionSection(val anchor: String, val label: String) {
    ANIMES("animes", "Animes"),
    DONGHUAS("donghuas", "Donghuas"),
    OVAS("ovas", "OVAs");

    companion object {
        fun fromAnchor(anchor: String): ProgramacionSection? =
            entries.firstOrNull { it.anchor.equals(anchor, ignoreCase = true) }
    }
}

/**
 * Tarjeta tal como aparece en "Programación": es un episodio recién subido,
 * no un anime. El slug es el del anime, el número es el del episodio.
 */
data class AnimeCard(
    val slug: String,
    val title: String,
    val episodeNumber: Int?,
    val episodeUrl: String,
    val thumbUrl: String?,
    val posterUrl: String?,
    val dateLabel: String?,
    val section: ProgramacionSection,
) {
    val animeUrl: String get() = Jk.animeUrl(slug)
}

/** Estado de emisión tal como lo publica el sitio. */
enum class AiringStatus(val raw: String, val label: String) {
    CURRENTLY("currently", "En emisión"),
    FINISHED("finished", "Concluido"),
    UPCOMING("upcoming", "Por estrenar"),
    UNKNOWN("", "—");

    companion object {
        fun fromRaw(raw: String?): AiringStatus =
            entries.firstOrNull { it.raw.equals(raw, ignoreCase = true) } ?: UNKNOWN
    }
}

data class AnimeDetail(
    val animeId: Int,
    val slug: String,
    val title: String,
    val titleAlt: String?,
    val synopsis: String?,
    val posterUrl: String?,
    val type: String?,
    val status: AiringStatus,
    val statusLabel: String?,
    val genres: List<String>,
    val studios: List<String>,
    val demographics: List<String>,
    val languages: List<String>,
    val totalEpisodes: Int?,
    val duration: String?,
    val airedOn: String?,
    val quality: String?,
    val nextEpisodeLabel: String?,
    val lastEpisodeNumber: Int?,
    val lastEpisodeUrl: String?,
)

data class Episode(
    val id: Long,
    val animeId: Int,
    val number: Double,
    val title: String,
    val thumbUrl: String?,
    val airedAt: String?,
    val url: String,
) {
    /** "5" o "12.5" según corresponda. */
    val numberLabel: String
        get() = if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()
}

data class EpisodesPage(
    val currentPage: Int,
    val lastPage: Int,
    val total: Int,
    val episodes: List<Episode>,
) {
    val hasNext: Boolean get() = currentPage < lastPage
}

enum class ServerKind {
    /** Embed reproducible en WebView (Streamwish, VOE, Filemoon, ...). */
    STREAM,

    /** Solo descarga (Mega, Mediafire). No sirve para ver. */
    DOWNLOAD,

    /** Player propio del sitio (jkplayer). */
    JKPLAYER,

    /** Último recurso: la página del episodio en el navegador. */
    WEB,
}

data class VideoServer(
    val name: String,
    val url: String,
    val lang: Int = 1,
    val size: String? = null,
    val kind: ServerKind = ServerKind.STREAM,
) {
    val langLabel: String
        get() = when (lang) {
            2 -> "LAT"
            3 -> "CAST"
            else -> "SUB"
        }

    val displayName: String get() = "$name · $langLabel"
}

/** Un servidor que no es de descarga ni el player propio: candidato a reproducción directa. */
val VideoServer.isPlayable: Boolean
    get() = kind == ServerKind.STREAM || kind == ServerKind.JKPLAYER || kind == ServerKind.WEB
