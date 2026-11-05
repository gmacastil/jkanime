package net.jkanime.tv.data.local

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Favoritos. Requerimiento 4 del brief.
 *
 * Se guarda lo mínimo para pintar la tarjeta sin volver a pedir el detalle:
 * título, poster y el último episodio que el usuario vio. `totalEpisodes` y
 * `lastAiredEpisode` son la base del badge de "episodios pendientes" (F5).
 */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val slug: String,
    @ColumnInfo(name = "anime_id") val animeId: Int,
    val title: String,
    @ColumnInfo(name = "poster_url") val posterUrl: String?,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    /** Último episodio visto por el usuario (no el último emitido). */
    @ColumnInfo(name = "last_watched_episode") val lastWatchedEpisode: Int?,
    /** Último episodio emitido según el sitio, refrescado por el sync de F5. */
    @ColumnInfo(name = "last_aired_episode") val lastAiredEpisode: Int?,
    @ColumnInfo(name = "total_episodes") val totalEpisodes: Int?,
    @ColumnInfo(name = "status_label") val statusLabel: String?,
    @ColumnInfo(name = "synced_at") val syncedAt: Long,
) {
    /**
     * Episodios que el usuario tiene pendientes. `null` cuando todavía no hay
     * datos suficientes (anime sin total conocido o sin sync hecho).
     */
    val pendingEpisodes: Int?
        get() {
            val aired = lastAiredEpisode ?: return null
            val seen = lastWatchedEpisode ?: 0
            return (aired - seen).coerceAtLeast(0)
        }
}

/**
 * Progreso de reproducción por episodio. `positionMs` es la posición actual;
 * cuando el episodio termina se guarda `completed = true`.
 */
@Entity(tableName = "progress", primaryKeys = ["slug", "episode"])
data class ProgressEntity(
    val slug: String,
    val episode: Int,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    val completed: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    val fraction: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY added_at DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE slug = :slug")
    fun observe(slug: String): Flow<FavoriteEntity?>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE slug = :slug)")
    fun observeIsFavorite(slug: String): Flow<Boolean>

    @Query("SELECT * FROM favorites")
    suspend fun all(): List<FavoriteEntity>

    @Upsert
    suspend fun upsert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE slug = :slug")
    suspend fun delete(slug: String)

    @Query("UPDATE favorites SET last_watched_episode = :episode WHERE slug = :slug")
    suspend fun setLastWatched(slug: String, episode: Int)

    @Query(
        "UPDATE favorites SET last_aired_episode = :aired, total_episodes = :total, " +
            "status_label = :status, synced_at = :now WHERE slug = :slug",
    )
    suspend fun setAired(
        slug: String,
        aired: Int?,
        total: Int?,
        status: String?,
        now: Long,
    )
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress WHERE slug = :slug AND episode = :episode")
    suspend fun get(slug: String, episode: Int): ProgressEntity?

    @Query("SELECT * FROM progress WHERE slug = :slug ORDER BY episode DESC")
    fun observeFor(slug: String): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE slug = :slug ORDER BY updated_at DESC LIMIT 1")
    suspend fun lastFor(slug: String): ProgressEntity?

    @Upsert
    suspend fun upsert(progress: ProgressEntity)
}

@Database(
    entities = [FavoriteEntity::class, ProgressEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class JkDatabase : RoomDatabase() {
    abstract fun favorites(): FavoriteDao
    abstract fun progress(): ProgressDao

    companion object {
        @Volatile
        private var instance: JkDatabase? = null

        fun get(context: Context): JkDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    JkDatabase::class.java,
                    "jk-tv.db",
                ).build().also { instance = it }
            }
    }
}
