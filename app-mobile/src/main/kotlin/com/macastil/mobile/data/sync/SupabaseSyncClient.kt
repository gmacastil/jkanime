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

    /** Pull: lee datos remotos. Por ahora retorna lista vacía. */
    suspend fun pull(credentials: SyncCredentials): RemoteSnapshot =
        withContext(Dispatchers.IO) {
            RemoteSnapshot(favorites = emptyList(), progress = emptyList())
        }

    suspend fun push(
        credentials: SyncCredentials,
        favorites: List<FavoriteEntity>,
        progress: List<ProgressEntity>,
    ) = withContext(Dispatchers.IO) {
        val favoritesJson = JSONArray()
        favorites.forEach { fav ->
            favoritesJson.put(
                JSONObject()
                    .put("slug", fav.slug)
                    .put("title", fav.title)
                    .put("anime_id", fav.animeId)
                    .put("poster_url", fav.posterUrl)
                    .put("added_at", fav.addedAt)
                    .put("last_watched_episode", fav.lastWatchedEpisode)
                    .put("last_aired_episode", fav.lastAiredEpisode)
                    .put("total_episodes", fav.totalEpisodes)
                    .put("status_label", fav.statusLabel)
                    .put("updated_at", fav.updatedAt)
                    .put("deleted_at", fav.deletedAt)
                    .put("owner", 0),
            )
        }

        val progressJson = JSONArray()
        progress.forEach { prog ->
            progressJson.put(
                JSONObject()
                    .put("slug", prog.slug)
                    .put("episode", prog.episode)
                    .put("position_ms", prog.positionMs)
                    .put("duration_ms", prog.durationMs)
                    .put("completed", prog.completed)
                    .put("updated_at", prog.updatedAt)
                    .put("owner", 0),
            )
        }

        val response = rpc(
            "sync_push",
            JSONObject()
                .put("p_code", credentials.code)
                .put("p_token", credentials.token)
                .put("favorites", favoritesJson)
                .put("progress", progressJson),
        )

        RemoteSnapshot(
            favorites = emptyList(),
            progress = emptyList(),
        )
    }
}

/** Función de fábrica para crear el cliente desde BuildConfig. */
fun createSyncClient(context: Context): SupabaseSyncClient {
    val baseUrl = BuildConfig.SUPABASE_URL
    val anonKey = BuildConfig.SUPABASE_ANON_KEY
    return SupabaseSyncClient(baseUrl, anonKey, context)
}
