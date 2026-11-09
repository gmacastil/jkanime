package net.jkanime.tv.data.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jkanime.tv.BuildConfig
import net.jkanime.tv.data.local.FavoriteEntity
import net.jkanime.tv.data.local.ProgressEntity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Credenciales del "sync space" compartido entre dispositivos. */
data class SyncCredentials(val code: String, val token: String)

/** Código temporal de un solo uso para vincular otro dispositivo. */
data class PairingCode(val code: String, val expiresAt: Long)

data class RemoteSnapshot(
    val favorites: List<FavoriteEntity>,
    val progress: List<ProgressEntity>,
)

/**
 * Cliente de Supabase por PostgREST, llamando a las RPC de
 * `docs/supabase/schema.sql`.
 *
 * No se usa Supabase Auth a propósito: la app corre en Android TV y teclear
 * email+password con el D-pad es inviable. La identidad es un código de 8
 * dígitos + un token; las RPC validan ambos del lado del servidor. La anon key
 * del APK no es un secreto (es pública por diseño), el secreto es el token.
 *
 * Se usa OkHttp directo y `org.json` en vez del cliente oficial de Supabase
 * para no añadir dependencias: son tres POST.
 */
class SupabaseSyncClient(
    private val baseUrl: String,
    private val anonKey: String,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && anonKey.isNotBlank()

    suspend fun createSpace(): SyncCredentials = withContext(Dispatchers.IO) {
        val response = rpc("sync_create", JSONObject())
        SyncCredentials(
            code = response.getString("code"),
            token = response.getString("token"),
        )
    }

    /**
     * Genera un código temporal (8 dígitos, 15 min, un solo uso) para vincular
     * otro dispositivo. El token nunca se teclea: sería un hex de 64
     * caracteres y en una TV no hay teclado.
     */
    suspend fun newPairingCode(credentials: SyncCredentials): PairingCode =
        withContext(Dispatchers.IO) {
            val response = rpc(
                "sync_new_pairing",
                JSONObject()
                    .put("p_code", credentials.code)
                    .put("p_token", credentials.token),
            )
            PairingCode(
                code = response.getString("pairing_code"),
                expiresAt = response.optLong("expires_at", 0L),
            )
        }

    /** Canjea un código de emparejamiento por las credenciales del espacio. */
    suspend fun joinWithCode(pairingCode: String): SyncCredentials =
        withContext(Dispatchers.IO) {
            val response = rpc(
                "sync_join",
                JSONObject().put("p_pairing_code", pairingCode.trim()),
            )
            SyncCredentials(
                code = response.getString("code"),
                token = response.getString("token"),
            )
        }

    suspend fun pull(credentials: SyncCredentials): RemoteSnapshot = withContext(Dispatchers.IO) {
        val response = rpc(
            "sync_pull",
            JSONObject()
                .put("p_code", credentials.code)
                .put("p_token", credentials.token),
        )

        val favorites = response.optJSONArray("favorites").orEmptyArray().mapObjects { obj ->
            FavoriteEntity(
                slug = obj.getString("slug"),
                animeId = obj.optInt("anime_id", 0),
                title = obj.optString("title"),
                posterUrl = obj.optStringOrNull("poster_url"),
                addedAt = obj.optLong("added_at", 0L),
                lastWatchedEpisode = obj.optIntOrNull("last_watched_episode"),
                lastAiredEpisode = obj.optIntOrNull("last_aired_episode"),
                totalEpisodes = obj.optIntOrNull("total_episodes"),
                statusLabel = obj.optStringOrNull("status_label"),
                syncedAt = obj.optLong("synced_at", 0L),
                updatedAt = obj.optLong("updated_at", 0L),
                deletedAt = obj.optLongOrNull("deleted_at"),
            )
        }

        val progress = response.optJSONArray("progress").orEmptyArray().mapObjects { obj ->
            ProgressEntity(
                slug = obj.getString("slug"),
                episode = obj.getInt("episode"),
                positionMs = obj.optLong("position_ms", 0L),
                durationMs = obj.optLong("duration_ms", 0L),
                completed = obj.optBoolean("completed", false),
                updatedAt = obj.optLong("updated_at", 0L),
            )
        }

        RemoteSnapshot(favorites = favorites, progress = progress)
    }

    suspend fun push(
        credentials: SyncCredentials,
        favorites: List<FavoriteEntity>,
        progress: List<ProgressEntity>,
    ): Unit = withContext(Dispatchers.IO) {
        rpc(
            "sync_push",
            JSONObject()
                .put("p_code", credentials.code)
                .put("p_token", credentials.token)
                .put("p_favorites", JSONArray().apply { favorites.forEach { put(it.toJson()) } })
                .put("p_progress", JSONArray().apply { progress.forEach { put(it.toJson()) } }),
        )
    }

    private fun rpc(function: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/rest/v1/rpc/$function")
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer $anonKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // PostgREST devuelve {"code":..,"message":..}. Se muestra solo
                // el `message` (p.ej. "invalid_credentials") en vez del JSON
                // crudo, que en pantalla de TV es ilegible.
                throw SyncException(friendlyError(response.code, text))
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }
}

class SyncException(message: String) : Exception(message)

/**
 * Traduce la respuesta de error de PostgREST a algo que quepa en pantalla.
 * Si la función no existe (PGRST202) es que falta ejecutar el schema.sql.
 */
private fun friendlyError(httpCode: Int, body: String): String {
    val parsed = runCatching { JSONObject(body) }.getOrNull()
    val serverMessage = parsed?.optString("message").orEmpty()
    val code = parsed?.optString("code").orEmpty()

    return when {
        code == "PGRST202" || (httpCode == 404 && serverMessage.contains("Could not find the function")) ->
            "Falta ejecutar docs/supabase/schema.sql en Supabase (funcion ausente)"
        serverMessage.contains("invalid_pairing_code", ignoreCase = true) ->
            "Código inválido o vencido. 07951576 es el código del espacio; genera un código temporal desde el otro dispositivo."
        serverMessage.isNotBlank() -> serverMessage
        else -> "HTTP $httpCode: ${body.take(120)}"
    }
}

private fun FavoriteEntity.toJson(): JSONObject = JSONObject()
    .put("slug", slug)
    .put("anime_id", animeId)
    .put("title", title)
    .put("poster_url", posterUrl)
    .put("last_watched_episode", lastWatchedEpisode)
    .put("last_aired_episode", lastAiredEpisode)
    .put("total_episodes", totalEpisodes)
    .put("status_label", statusLabel)
    .put("updated_at", updatedAt)
    .put("deleted_at", deletedAt)

private fun ProgressEntity.toJson(): JSONObject = JSONObject()
    .put("slug", slug)
    .put("episode", episode)
    .put("position_ms", positionMs)
    .put("duration_ms", durationMs)
    .put("completed", completed)
    .put("updated_at", updatedAt)

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() && it != "null" }

private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)

private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)

private fun JSONArray?.orEmptyArray(): JSONArray = this ?: JSONArray()

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    (0 until length()).mapNotNull { index -> optJSONObject(index)?.let(transform) }

/**
 * Persistencia de las credenciales del sync space. En SharedPreferences y no
 * en Room porque son configuración del dispositivo, no datos de catálogo: no
 * deben entrar en el merge.
 */
class SyncCredentialsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("jk-sync", Context.MODE_PRIVATE)

    var credentials: SyncCredentials?
        get() {
            val code = prefs.getString(KEY_CODE, null) ?: return null
            val token = prefs.getString(KEY_TOKEN, null) ?: return null
            return SyncCredentials(code, token)
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove(KEY_CODE)
                    remove(KEY_TOKEN)
                } else {
                    putString(KEY_CODE, value.code)
                    putString(KEY_TOKEN, value.token)
                }
            }.apply()
        }

    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    companion object {
        private const val KEY_CODE = "code"
        private const val KEY_TOKEN = "token"
        private const val KEY_LAST_SYNC = "last_sync_at"

        fun client(): SupabaseSyncClient =
            SupabaseSyncClient(
                baseUrl = BuildConfig.SUPABASE_URL,
                anonKey = BuildConfig.SUPABASE_ANON_KEY,
            )
    }
}
