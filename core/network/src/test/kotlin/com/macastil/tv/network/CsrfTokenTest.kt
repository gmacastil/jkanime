package com.macastil.tv.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CsrfTokenTest {

    @Test
    fun `extrae el token del meta tag`() {
        val html = """<head><meta name="csrf-token" content="abc123"></head>"""
        assertEquals("abc123", JkHttpClient.extractCsrfToken(html))
    }

    @Test
    fun `extrae el token de un input hidden`() {
        val html = """<form><input type="hidden" name="_token" value="xyz789"></form>"""
        assertEquals("xyz789", JkHttpClient.extractCsrfToken(html))
    }

    @Test
    fun `devuelve null si no hay token`() {
        assertNull(JkHttpClient.extractCsrfToken("<html><body>sin token</body></html>"))
    }

    @Test
    fun `resuelve urls relativas`() {
        assertEquals("https://jkanime.net/a/b/", JkHttpClient.absolute("/a/b/"))
        assertEquals("https://cdn.x/y.jpg", JkHttpClient.absolute("//cdn.x/y.jpg"))
        assertEquals("https://otro.com/x", JkHttpClient.absolute("https://otro.com/x"))
    }
}
