package com.macastil.mobile.data.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.macastil.mobile.BuildConfig
import com.macastil.mobile.data.local.FavoriteEntity
import com.macastil.mobile.data.local.ProgressEntity
import com.macastil.mobile.data.local.SyncCredentialsEntity
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

/** Clave de vinculación definida por el usuario (6 caracteres hex). */
data class RecoveryKey(val key: String)

/** Snapshot remoto de favoritos y progreso. */
data class RemoteSnapshot(
    val favorites: List<FavoriteEntity>,
    val progress: List<ProgressEntity>,
)

/**
 * Cliente de Supabase por PostgREST, llamando a las RPC de
 * docs/supabase/schema.sql.
 *
 * Usa OkHttp directo y org.json para no añadir dependencias pesadas.
 */
class SupabaseSyncClient(
    private val baseUrl: String,
    private val anonKey: String,
    private val context: Context,
) {

    private val appContext = context.applicationContext

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    val isConfigured: Boolean
        get() = baseUrl.startsWith("http") && anonKey.isNotBlank()

    private suspend fun rpc(function: String, params: JSONObject): JSONObject {
        val url = "$baseUrl/rest/v1/rpc/$function"
        val body = params.toString().toRequestBody(jsonMedia)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer $anonKey")
            .addHeader("Content-Type", "application/json")
            .build()
        val response = http.newCall(request).execute()
        val responseBody = response.use { it.body?.string() ?: "" }
        return JSONObject(responseBody)
    }

    suspend fun createSpace(): SyncCredentials = withContext(Dispatchers.IO) {
        val response = rpc("sync_create", JSONObject())
        SyncCredentials(
            code = response.getString("code"),
            token = response.getString("token"),
        )
    }

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

    suspend fun setRecoveryKey(
        credentials: SyncCredentials,
        recoveryKey: String,
    ): RecoveryKey =
        withContext(Dispatchers.IO) {
            val response = rpc(
                "sync_set_recovery",
                JSONObject()
                    .put("p_code", credentials.code)
                    .put("p_token", credentials.token)
                    .put("p_recovery_key", recoveryKey.trim().uppercase()),
            )
            RecoveryKey(response.optString("recovery_key", recoveryKey.trim().uppercase()))
        }

    suspend fun recoverWithKey(recoveryKey: String): SyncCredentials =
        withContext(Dispatchers.IO) {
            val response = rpc(
                "sync_recover",
                JSONObject().put("p_recovery_key", recoveryKey.trim()),
            )
            SyncCredentials(
                code = response.getString("code"),
                token = response.getString("token"),
            )
        }

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

    /** Pull: foto remota del espacio (favoritos + progreso). */
    suspend fun pull(credentials: SyncCredentials): RemoteSnapshot =
        withContext(Dispatchers.IO) {
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
    ): PushResult = withContext(Dispatchers.IO) {
        val response = rpc(
            "sync_push",
            JSONObject()
                .put("p_code", credentials.code)
                .put("p_token", credentials.token)
                .put("p_favorites", JSONArray().apply { favorites.forEach { put(it.toJson()) } })
                .put("p_progress", JSONArray().apply { progress.forEach { put(it.toJson()) } }),
        )
        PushResult(
            favorites = response.optInt("favorites", favorites.size),
            progress = response.optInt("progress", progress.size),
        )
    }
}

/** Recuentos aplicados por `sync_push`. */
data class PushResult(val favorites: Int, val progress: Int)

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

/** Función de fábrica para crear el cliente desde BuildConfig. */
fun createSyncClient(context: Context): SupabaseSyncClient {
    val baseUrl = BuildConfig.SUPABASE_URL
    val anonKey = BuildConfig.SUPABASE_ANON_KEY
    return SupabaseSyncClient(baseUrl, anonKey, context)
}
