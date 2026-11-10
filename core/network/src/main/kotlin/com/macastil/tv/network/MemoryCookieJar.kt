package com.macastil.tv.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * CookieJar en memoria.
 *
 * Laravel exige que el GET que genera el token CSRF y el POST que consume el
 * endpoint AJAX viajen en la MISMA sesión. Basta con mantenerlas durante la vida
 * del proceso: al reiniciar la app se negocia una sesión nueva.
 */
internal class MemoryCookieJar : CookieJar {

    private val cookies = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(this) {
            for (cookie in cookies) {
                this.cookies.removeAll {
                    it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                }
                this.cookies.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(this) {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt < now }
        cookies.filter { it.matches(url) }
    }

    fun clear() = synchronized(this) { cookies.clear() }
}
