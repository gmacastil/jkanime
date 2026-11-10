package net.jkanime.tv.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jkanime.tv.model.Jk
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP único para jkanime.net.
 *
 * Responsabilidades:
 *  - cookie jar de sesión (Laravel),
 *  - rate limiting (1 req / 700 ms + jitter),
 *  - UA de dispositivo TV real,
 *  - extracción del token CSRF que necesitan los endpoints AJAX.
 */
class JkHttpClient(
    private val userAgent: String = Jk.USER_AGENT,
    minIntervalMs: Long = 700L,
) {

    private val cookieJar = MemoryCookieJar()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(RateLimitInterceptor(minIntervalMs))
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .apply {
                    // No pisar un Accept explícito (getText usa `*/*` para
                    // pedir m3u8/manifests, no HTML).
                    if (chain.request().header("Accept") == null) {
                        header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                    }
                }
                .build()
            chain.proceed(request)
        }
        .build()

    suspend fun getHtml(url: String, referer: String? = null): String =
        execute(Request.Builder().url(url).get(), referer)

    /**
     * GET genérico (Accept todo) para assets que no son HTML: m3u8,
     * manifests, JSON de APIs de terceros.
     */
    suspend fun getText(url: String, referer: String? = null): String {
        val request = Request.Builder().url(url).get().header("Accept", "*/*")
        return execute(request, referer)
    }

    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String {
        val body = FormBody.Builder().apply {
            form.forEach { (key, value) -> add(key, value) }
        }.build()

        val request = Request.Builder().url(url).post(body)
        extraHeaders.forEach { (key, value) -> request.header(key, value) }

        return execute(request, referer)
    }

    /**
     * Descarga la página, la deja en la cookie jar y devuelve su token CSRF.
     * Sin este paso el POST a /ajax/episodes responde 419.
     */
    suspend fun csrfToken(pageUrl: String): String {
        val html = getHtml(pageUrl)
        return extractCsrfToken(html)
            ?: throw JkCsrfTokenMissingException(pageUrl)
    }

    suspend fun resetSession() {
        cookieJar.clear()
    }

    private suspend fun execute(builder: Request.Builder, referer: String?): String {
        if (referer != null) builder.header("Referer", referer)
        val request = builder.build()

        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw JkHttpException(
                        statusCode = response.code,
                        url = request.url.toString(),
                        bodySnippet = body.take(300),
                    )
                }
                body
            }
        }
    }

    companion object {
        private val META_CSRF = Regex(
            """<meta\s+name=["']csrf-token["']\s+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        private val INPUT_TOKEN = Regex(
            """<input[^>]+name=["']_token["'][^>]+value=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        private val INPUT_TOKEN_REVERSED = Regex(
            """<input[^>]+value=["']([^"']+)["'][^>]+name=["']_token["']""",
            RegexOption.IGNORE_CASE,
        )

        fun extractCsrfToken(html: String): String? =
            META_CSRF.find(html)?.groupValues?.get(1)
                ?: INPUT_TOKEN.find(html)?.groupValues?.get(1)
                ?: INPUT_TOKEN_REVERSED.find(html)?.groupValues?.get(1)

        fun absolute(url: String, base: String = Jk.BASE_URL): String = when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> base.trimEnd('/') + url
            else -> "$base/$url"
        }
    }
}
