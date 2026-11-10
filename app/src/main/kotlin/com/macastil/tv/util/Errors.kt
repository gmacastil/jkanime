package com.macastil.tv.util

import android.content.Context
import com.macastil.tv.R
import com.macastil.tv.network.JkCsrfTokenMissingException
import com.macastil.tv.network.JkHttpException
import com.macastil.tv.network.JkParseException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Traduce errores técnicos a algo que un usuario de TV pueda entender.
 *
 * Recibe `Context` (y no es `@Composable`) porque se llama desde los
 * ViewModel, fuera de la composición: así el mensaje sale ya localizado
 * (es/en) según la configuración del dispositivo.
 */
fun Throwable.friendlyMessage(context: Context): String = when (this) {
    is JkHttpException -> when (val code = statusCode) {
        403 -> context.getString(R.string.error_http_403)
        404 -> context.getString(R.string.error_http_404)
        419 -> context.getString(R.string.error_http_419)
        429 -> context.getString(R.string.error_http_429)
        in 500..599 -> context.getString(R.string.error_http_5xx, code)
        else -> context.getString(R.string.error_http_other, code)
    }
    is JkCsrfTokenMissingException ->
        context.getString(R.string.error_csrf)
    is JkParseException ->
        context.getString(R.string.error_parse)
    is UnknownHostException -> context.getString(R.string.error_no_network)
    is SocketTimeoutException -> context.getString(R.string.error_timeout)
    else -> message ?: context.getString(R.string.error_unknown)
}
