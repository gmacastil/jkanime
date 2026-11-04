package net.jkanime.tv.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Serializa las peticiones al sitio: 1 request cada [minIntervalMs] + jitter.
 * El sitio tiene anti-bot; paralelizar agresivo es la vía rápida a un 403.
 */
internal class RateLimitInterceptor(
    private val minIntervalMs: Long,
    private val jitterMs: Long = 250L,
) : Interceptor {

    private val lock = Any()
    private var lastRequestAt = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val wait = minIntervalMs - (now - lastRequestAt)
            if (wait > 0) {
                Thread.sleep(wait + (0..jitterMs).random())
            }
            lastRequestAt = System.currentTimeMillis()
        }
        return chain.proceed(chain.request())
    }
}
