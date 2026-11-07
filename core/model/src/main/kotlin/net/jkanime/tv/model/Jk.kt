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

    /**
     * Buscador del sitio. `GET /buscar?q=` responde 302 a `/buscar/{consulta}`,
     * que es HTML con hasta 30 fichas (`div.anime__item`). Se pide la ruta
     * final directamente para no gastar una redirección (el rate limit es de
     * 700 ms por petición).
     */
    fun searchUrl(query: String): String {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return "$BASE_URL/buscar/$encoded"
    }
    const val DIRECTORY_URL = "$BASE_URL/directorio"
    const val SCHEDULE_URL = "$BASE_URL/horario"

    /** UA de dispositivo TV real: el sitio tiene detección de bots. */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
}
