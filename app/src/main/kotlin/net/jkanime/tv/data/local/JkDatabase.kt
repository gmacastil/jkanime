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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Favoritos. Requerimiento 4 del brief.
 *
 * `updatedAt` y `deletedAt` existen para la sincronización multi-dispositivo
 * (F6): sin `updatedAt` no se puede resolver un conflicto entre dispositivos, y
 * sin borrado lógico un dispositivo que no vio el borrado resucitaría el
 * favorito en el siguiente push.
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
    /** Marca de tiempo del último cambio; base del merge last-write-wins. */
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0,
    /** No nulo = borrado en algún dispositivo (tombstone para el merge). */
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
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
    @Query("SELECT * FROM favorites WHERE deleted_at IS NULL ORDER BY added_at DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE slug = :slug")
    fun observe(slug: String): Flow<FavoriteEntity?>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM favorites WHERE slug = :slug AND deleted_at IS NULL)",
    )
    fun observeIsFavorite(slug: String): Flow<Boolean>

    @Query("SELECT * FROM favorites WHERE deleted_at IS NULL")
    suspend fun all(): List<FavoriteEntity>

    /** Incluye tombstones: es lo que se empuja al backend. */
    @Query("SELECT * FROM favorites")
    suspend fun allIncludingDeleted(): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE slug = :slug")
    suspend fun get(slug: String): FavoriteEntity?

    @Upsert
    suspend fun upsert(favorite: FavoriteEntity)

    @Query("UPDATE favorites SET deleted_at = :now, updated_at = :now WHERE slug = :slug")
    suspend fun softDelete(slug: String, now: Long)

    @Query(
        "UPDATE favorites SET last_watched_episode = :episode, updated_at = :now " +
            "WHERE slug = :slug",
    )
    suspend fun setLastWatched(slug: String, episode: Int, now: Long)

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

    @Query("SELECT * FROM progress")
    suspend fun all(): List<ProgressEntity>

    @Upsert
    suspend fun upsert(progress: ProgressEntity)
}

@Database(
    entities = [FavoriteEntity::class, ProgressEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class JkDatabase : RoomDatabase() {
    abstract fun favorites(): FavoriteDao
    abstract fun progress(): ProgressDao

    companion object {
        /** v1 → v2: columnas para el merge multi-dispositivo. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE favorites ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("ALTER TABLE favorites ADD COLUMN deleted_at INTEGER")
                db.execSQL("UPDATE favorites SET updated_at = added_at WHERE updated_at = 0")
            }
        }

        @Volatile
        private var instance: JkDatabase? = null

        fun get(context: Context): JkDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    JkDatabase::class.java,
                    "jk-tv.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
