package net.jkanime.tv.model

/**
 * Constantes del sitio. Centralizadas para que un cambio de dominio sea un solo edit.
 */
object Jk {
    const val BASE_URL = "https://jkanime.net"

    /** Home: contiene las 4 listas de "Programación" renderizadas en el HTML. */
    const val HOME_URL = "$BASE_URL/"

    /** Detalle del anime. */
    fun animeUrl(slug: String) = "$BASE_URL/$slug/"

    /** Episodio concreto. */
    fun episodeUrl(slug: String, episode: Int) = "$BASE_URL/$slug/$episode/"

    /** Endpoint AJAX (POST + CSRF) que devuelve la lista de capítulos paginada. */
    fun episodesAjaxUrl(animeId: Int) = "$BASE_URL/ajax/episodes/$animeId/"

    /** CDN de pósters (vertical) y de thumbnails de episodio (16:9). */
    const val CDN_POSTER = "https://cdn.jkdesa.com/assets/images/animes/image/"
    const val CDN_VIDEO = "https://cdn.jkdesa.com/assets/images/animes/video/image/"

    const val SEARCH_AJAX_URL = "$BASE_URL/ajax_search"
    const val DIRECTORY_URL = "$BASE_URL/directorio"
    const val SCHEDULE_URL = "$BASE_URL/horario"

    /** UA de dispositivo TV real: el sitio tiene detección de bots. */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
}
