package com.macastil.tv.model

/**
 * Una opción de un filtro del directorio (`<option>` de un `<select>`).
 *
 * `value` es lo que viaja en la query string; el `<option>` del sitio a veces
 * no trae atributo `value` (las letras y los años usan el texto como valor).
 */
data class DirectorioOption(
    val value: String,
    val label: String,
) {
    /** La opción vacía es el "sin filtro" de cada grupo. */
    val isDefault: Boolean get() = value.isBlank()
}

/**
 * Un grupo de filtros del directorio, tal como lo publica el sitio
 * (`genero`, `letra`, `tipo`, `estado`…).
 */
data class DirectorioFilter(
    val name: String,
    val label: String,
    val options: List<DirectorioOption>,
) {
    fun labelFor(value: String): String? =
        options.firstOrNull { it.value == value }?.label
}

/**
 * Página del directorio: catálogo de filtros + 30 fichas.
 *
 * El sitio no pagina los filtros (el formulario es el mismo en todas las
 * páginas) y devuelve los resultados en un JSON embebido en el HTML.
 */
data class DirectorioPage(
    val filters: List<DirectorioFilter>,
    val hits: List<AnimeSearchHit>,
    val page: Int,
    val lastPage: Int,
    val total: Int,
)
