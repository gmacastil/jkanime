package net.jkanime.tv.util

import net.jkanime.tv.network.JkCsrfTokenMissingException
import net.jkanime.tv.network.JkHttpException
import net.jkanime.tv.network.JkParseException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Traduce errores técnicos a algo que un usuario de TV pueda entender. */
fun Throwable.friendlyMessage(): String = when (this) {
    is JkHttpException -> when (val code = statusCode) {
        403 -> "El sitio bloqueó la petición (403). Espera un momento y reintenta."
        404 -> "No se encontró el contenido (404)."
        419 -> "La sesión del sitio expiró (419). Reintenta."
        429 -> "Demasiadas peticiones (429). Espera un minuto."
        in 500..599 -> "El sitio está caído (HTTP $code)."
        else -> "Error HTTP $code."
    }
    is JkCsrfTokenMissingException ->
        "El sitio no entregó el token de sesión. Reintenta en unos minutos."
    is JkParseException ->
        "El sitio cambió su estructura y no se pudo leer la página."
    is UnknownHostException -> "Sin conexión a internet."
    is SocketTimeoutException -> "El sitio tardó demasiado en responder."
    else -> message ?: "Error desconocido."
}
