package com.macastil.tv.model

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

    /**
     * Endpoint AJAX (POST + CSRF) que devuelve la lista de capítulos paginada.
     *
     * La página va **en la ruta**, no en la query: el propio JS del sitio
     * pide `https://jkanime.net/ajax/episodes/{id}/{pagina}`. Con `?page=N`
     * o `?p=N` el servidor ignora el valor y devuelve siempre la página 1
     * (comprobado con curl: `/ajax/episodes/142/2` devuelve los capítulos
     * 17-32 y `/ajax/episodes/142/32` los 497-500).
     */
    fun episodesAjaxUrl(animeId: Int, page: Int = 1) =
        if (page > 1) "$BASE_URL/ajax/episodes/$animeId/$page" else "$BASE_URL/ajax/episodes/$animeId/"

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

    /**
     * Directorio con filtros. El formulario del sitio es un GET con un
     * `<select>` por filtro (`genero`, `letra`, `tipo`, `estado`, `fecha`…) y
     * la paginación va en `p`. Los filtros vacíos se omiten para no ensuciar
     * la URL ni romper el caché del repositorio.
     */
    fun directorioUrl(filters: Map<String, String> = emptyMap(), page: Int = 1): String {
        val params = filters
            .filterValues { it.isNotBlank() }
            .map { (key, value) -> "$key=${java.net.URLEncoder.encode(value, "UTF-8")}" }
            .toMutableList()
        if (page > 1) params += "p=$page"
        return if (params.isEmpty()) DIRECTORY_URL else "$DIRECTORY_URL?${params.joinToString("&")}"
    }
    const val SCHEDULE_URL = "$BASE_URL/horario"

    /** UA de dispositivo TV real: el sitio tiene detección de bots. */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
}
