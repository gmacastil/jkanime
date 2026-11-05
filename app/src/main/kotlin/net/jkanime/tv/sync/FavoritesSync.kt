package net.jkanime.tv.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.jkanime.tv.Graph
import net.jkanime.tv.data.local.LibraryRepository
import java.util.concurrent.TimeUnit

/**
 * F5: refresca los favoritos cada 6 h para poder marcar episodios pendientes.
 *
 * No descarga episodios ni guarda HTML: solo pide el detalle de cada anime
 * (con la cache TTL del repositorio por delante) y actualiza
 * `last_aired_episode` / `total_episodes` / `status_label` en Room. Si un
 * favorito falla, se sigue con el resto: un anime caído no debe dejar sin
 * badge a los demás.
 */
class FavoritesSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val library: LibraryRepository = Graph.library(applicationContext)
        val repository = Graph.repository

        val favorites = library.favoritesSnapshot()
        if (favorites.isEmpty()) return Result.success()

        var failures = 0
        for (fav in favorites) {
            runCatching { repository.detail(fav.slug, force = true) }
                .onSuccess { detail ->
                    library.updateAired(
                        slug = fav.slug,
                        lastAiredEpisode = detail.lastEpisodeNumber,
                        totalEpisodes = detail.totalEpisodes?.takeIf { it > 0 },
                        statusLabel = detail.statusLabel ?: detail.status.label,
                    )
                }
                .onFailure { failures++ }
        }

        android.util.Log.i("JkSync", "favoritos=${favorites.size} fallos=$failures")
        // Se reintenta solo si falló todo: si algo se actualizó, el resultado
        // ya es útil y no conviene reprogramar con backoff.
        return if (failures > 0 && failures == favorites.size) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "jkanime-favorites-sync"
        private const val INTERVAL_HOURS = 6L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FavoritesSyncWorker>(
                INTERVAL_HOURS,
                TimeUnit.HOURS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                // KEEP: si ya está programado, no se reinicia el intervalo en
                // cada arranque de la app (si no, nunca llegaría a ejecutarse
                // en un equipo que se abre a diario).
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
