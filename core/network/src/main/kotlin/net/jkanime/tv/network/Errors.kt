package net.jkanime.tv.network

import java.io.IOException

/** Error HTTP del sitio (403 anti-bot, 419 CSRF expirado, 5xx, ...). */
class JkHttpException(
    val statusCode: Int,
    val url: String,
    val bodySnippet: String? = null,
) : IOException("HTTP $statusCode en $url")

/** El HTML no trae el token CSRF esperado (cambio de plantilla o bloqueo). */
class JkCsrfTokenMissingException(url: String) :
    IOException("No se encontró el token CSRF en $url")

/** La respuesta no tiene el shape esperado (cambio de DOM o de JSON). */
class JkParseException(message: String) : IOException(message)
