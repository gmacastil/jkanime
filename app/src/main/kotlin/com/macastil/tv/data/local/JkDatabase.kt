package com.macastil.tv.data.local

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Perfil local (tipo Netflix) — F11 multiusuario.
 *
 * Cada perfil tiene su propia biblioteca (`owner` en favorites/progress) y su
 * propio sync space (`space_code`/`space_token`), así varias cuentas pueden
 * usar la misma TV sin mezclar datos. `owner = 0` es el modo legacy: datos
 * antiguos sin perfil, que el primer perfil creado se adjudica (claim).
 *
 * `pinHash` queda preparado para PIN opcional (pendiente: sin UI todavía).
 */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Int,
    val name: String,
    @ColumnInfo(name = "pin_hash") val pinHash: String? = null,
    /** Sync space asignado a este perfil (null = aún sin espacio). */
    @ColumnInfo(name = "space_code") val spaceCode: String? = null,
    @ColumnInfo(name = "space_token") val spaceToken: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long,
)

/**
 * Favoritos. Requerimiento 4 del brief.
 *
 * `updatedAt` y `deletedAt` existen para la sincronización multi-dispositivo
 * (F6): sin `updatedAt` no se puede resolver un conflicto entre dispositivos, y
 * sin borrado lógico un dispositivo que no vio el borrado resucitaría el
 * favorito en el siguiente push.
 *
 * `owner` (F11) separa la biblioteca por perfil; la PK es (owner, slug) para
 * que dos perfiles puedan favorecer el mismo anime.
 */
@Entity(tableName = "favorites", primaryKeys = ["owner", "slug"])
data class FavoriteEntity(
    /**
     * Perfil dueño de la fila; 0 = datos legacy sin perfil. El JSON remoto
     * llega con 0 y `LibraryRepository.merge*` le asigna el owner activo.
     */
    val owner: Int = 0,
    val slug: String,
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
 *
 * `owner` (F11): separa el progreso por perfil; la PK es (owner, slug,
 * episode).
 */
@Entity(tableName = "progress", primaryKeys = ["owner", "slug", "episode"])
data class ProgressEntity(
    /**
     * Perfil dueño de la fila; 0 = datos legacy sin perfil. El JSON remoto
     * llega con 0 y `LibraryRepository.merge*` le asigna el owner activo.
     */
    val owner: Int = 0,
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
    @Query("SELECT * FROM favorites WHERE owner = :owner AND deleted_at IS NULL ORDER BY added_at DESC")
    fun observeAll(owner: Int): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE owner = :owner AND slug = :slug")
    fun observe(owner: Int, slug: String): Flow<FavoriteEntity?>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM favorites WHERE owner = :owner AND slug = :slug " +
            "AND deleted_at IS NULL)",
    )
    fun observeIsFavorite(owner: Int, slug: String): Flow<Boolean>

    @Query("SELECT * FROM favorites WHERE owner = :owner AND deleted_at IS NULL")
    suspend fun all(owner: Int): List<FavoriteEntity>

    /** Incluye tombstones: es lo que se empuja al backend. */
    @Query("SELECT * FROM favorites WHERE owner = :owner")
    suspend fun allIncludingDeleted(owner: Int): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE owner = :owner AND slug = :slug")
    suspend fun get(owner: Int, slug: String): FavoriteEntity?

    @Upsert
    suspend fun upsert(favorite: FavoriteEntity)

    @Query(
        "UPDATE favorites SET deleted_at = :now, updated_at = :now " +
            "WHERE owner = :owner AND slug = :slug",
    )
    suspend fun softDelete(owner: Int, slug: String, now: Long)

    @Query(
        "UPDATE favorites SET last_watched_episode = :episode, updated_at = :now " +
            "WHERE owner = :owner AND slug = :slug",
    )
    suspend fun setLastWatched(owner: Int, slug: String, episode: Int, now: Long)

    @Query(
        "UPDATE favorites SET last_aired_episode = :aired, total_episodes = :total, " +
            "status_label = :status, synced_at = :now " +
            "WHERE owner = :owner AND slug = :slug",
    )
    suspend fun setAired(
        owner: Int,
        slug: String,
        aired: Int?,
        total: Int?,
        status: String?,
        now: Long,
    )

    // ---------------------------------------------------------------------
    // Migración de datos (F11): claim del modo legacy al primer perfil
    // ---------------------------------------------------------------------

    @Query("UPDATE favorites SET owner = :newOwner WHERE owner = 0")
    suspend fun claimLegacy(newOwner: Int)

    @Query("DELETE FROM favorites WHERE owner = :owner")
    suspend fun deleteForOwner(owner: Int)
}

@Dao
interface ProgressDao {
    @Query(
        "SELECT * FROM progress WHERE owner = :owner AND slug = :slug " +
            "AND episode = :episode",
    )
    suspend fun get(owner: Int, slug: String, episode: Int): ProgressEntity?

    @Query("SELECT * FROM progress WHERE owner = :owner AND slug = :slug ORDER BY episode DESC")
    fun observeFor(owner: Int, slug: String): Flow<List<ProgressEntity>>

    @Query(
        "SELECT * FROM progress WHERE owner = :owner AND slug = :slug " +
            "ORDER BY updated_at DESC LIMIT 1",
    )
    suspend fun lastFor(owner: Int, slug: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE owner = :owner")
    suspend fun all(owner: Int): List<ProgressEntity>

    @Upsert
    suspend fun upsert(progress: ProgressEntity)

    // F11: claim del modo legacy y borrado por perfil.

    @Query("UPDATE progress SET owner = :newOwner WHERE owner = 0")
    suspend fun claimLegacy(newOwner: Int)

    @Query("DELETE FROM progress WHERE owner = :owner")
    suspend fun deleteForOwner(owner: Int)
}

/**
 * Perfiles locales (F11). El perfil activo NO vive aquí: se guarda en
 * SharedPreferences (`jk-profiles`) para que cambiar de perfil sea un cambio
 * rápido y los Flows de `LibraryRepository` lo observen sin transacciones.
 */
@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY last_used_at DESC")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun observe(id: Int): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: Int): ProfileEntity?

    /** Perfil con más uso reciente (para reactivar tras borrar el activo). */
    @Query("SELECT * FROM profiles ORDER BY last_used_at DESC LIMIT 1")
    suspend fun first(): ProfileEntity?

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int

    @Insert
    suspend fun insert(profile: ProfileEntity): Long

    @Query("UPDATE profiles SET last_used_at = :now WHERE id = :id")
    suspend fun touch(id: Int, now: Long)

    @Query(
        "UPDATE profiles SET space_code = :code, space_token = :token WHERE id = :id",
    )
    suspend fun setSpace(id: Int, code: String, token: String)

    @Query("UPDATE profiles SET space_code = NULL, space_token = NULL WHERE id = :id")
    suspend fun clearSpace(id: Int)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: Int)
}

@Database(
    entities = [FavoriteEntity::class, ProgressEntity::class, ProfileEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class JkDatabase : RoomDatabase() {
    abstract fun favorites(): FavoriteDao
    abstract fun progress(): ProgressDao
    abstract fun profiles(): ProfileDao

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

        /**
         * v2 → v3 (F11): perfiles locales.
         *
         * SQLite no permite cambiar la PK con ALTER TABLE, así que ambas
         * tablas se recrean con `owner` como primera columna de la PK y las
         * filas existentes quedan como `owner = 0` (modo legacy). El primer
         * perfil creado las reclama con `claimLegacy`.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS favorites_new (" +
                        "owner INTEGER NOT NULL, " +
                        "slug TEXT NOT NULL, " +
                        "anime_id INTEGER NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "poster_url TEXT, " +
                        "added_at INTEGER NOT NULL, " +
                        "last_watched_episode INTEGER, " +
                        "last_aired_episode INTEGER, " +
                        "total_episodes INTEGER, " +
                        "status_label TEXT, " +
                        "synced_at INTEGER NOT NULL, " +
                        "updated_at INTEGER NOT NULL, " +
                        "deleted_at INTEGER, " +
                        "PRIMARY KEY(owner, slug))",
                )
                db.execSQL(
                    "INSERT INTO favorites_new (owner, slug, anime_id, title, poster_url, " +
                        "added_at, last_watched_episode, last_aired_episode, total_episodes, " +
                        "status_label, synced_at, updated_at, deleted_at) " +
                        "SELECT 0, slug, anime_id, title, poster_url, added_at, " +
                        "last_watched_episode, last_aired_episode, total_episodes, " +
                        "status_label, synced_at, updated_at, deleted_at FROM favorites",
                )
                db.execSQL("DROP TABLE favorites")
                db.execSQL("ALTER TABLE favorites_new RENAME TO favorites")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS progress_new (" +
                        "owner INTEGER NOT NULL, " +
                        "slug TEXT NOT NULL, " +
                        "episode INTEGER NOT NULL, " +
                        "position_ms INTEGER NOT NULL, " +
                        "duration_ms INTEGER NOT NULL, " +
                        "completed INTEGER NOT NULL, " +
                        "updated_at INTEGER NOT NULL, " +
                        "PRIMARY KEY(owner, slug, episode))",
                )
                db.execSQL(
                    "INSERT INTO progress_new (owner, slug, episode, position_ms, duration_ms, " +
                        "completed, updated_at) " +
                        "SELECT 0, slug, episode, position_ms, duration_ms, completed, " +
                        "updated_at FROM progress",
                )
                db.execSQL("DROP TABLE progress")
                db.execSQL("ALTER TABLE progress_new RENAME TO progress")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS profiles (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "pin_hash TEXT, " +
                        "space_code TEXT, " +
                        "space_token TEXT, " +
                        "created_at INTEGER NOT NULL, " +
                        "last_used_at INTEGER NOT NULL)",
                )
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
