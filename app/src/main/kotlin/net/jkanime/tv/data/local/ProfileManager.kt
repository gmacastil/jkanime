package net.jkanime.tv.data.local

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * Perfiles locales estilo Netflix (F11 multiusuario).
 *
 * Cada perfil es un dueño (`owner`) de su biblioteca en Room y de su sync
 * space. El perfil **activo** vive en SharedPreferences para que sea un cambio
 * síncrono y barato: al cambiarlo, los Flows de `LibraryRepository`
 * (flatMapLatest sobre `activeId`) re-emiten automáticamente con los datos del
 * nuevo dueño.
 *
 * `activeId = 0` es el modo legacy: sin perfil, la biblioteca única de antes.
 * El primer perfil creado reclama (`claimLegacy`) esos datos y, por el camino
 * del sync, también las credenciales de espacio del dispositivo.
 */
class ProfileManager(
    context: Context,
    private val db: JkDatabase,
) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _activeId = MutableStateFlow(prefs.getInt(KEY_ACTIVE, 0))

    private val _lang = MutableStateFlow(
        prefs.getString(AppLocale.KEY_LANG, AppLocale.SYSTEM) ?: AppLocale.SYSTEM,
    )

    /** Idioma elegido para la app: "system" | "es" | "en". */
    val lang: StateFlow<String> = _lang.asStateFlow()

    /**
     * Cambia el idioma de la app. Persiste la preferencia; para que surta
     * efecto hay que recrear la activity ([MainActivity] la envuelve con
     * [AppLocale.wrap] en `attachBaseContext`).
     */
    fun setLang(code: String) {
        _lang.value = code
        prefs.edit().putString(AppLocale.KEY_LANG, code).apply()
    }


    /** Owner activo; 0 = modo legacy (sin perfil). */
    val activeId: StateFlow<Int> = _activeId.asStateFlow()

    /** Perfiles ordenados por último uso. */
    val profiles: Flow<List<ProfileEntity>> = db.profiles().observeAll()

    /** Perfil activo como Flow (null = modo legacy). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val activeProfile: Flow<ProfileEntity?> = _activeId.flatMapLatest { id ->
        if (id == 0) flowOf(null) else db.profiles().observe(id)
    }

    /** Instantánea síncrona del owner activo (para DAOs suspend). */
    suspend fun owner(): Int = _activeId.value

    /** Instantánea del perfil activo (null = modo legacy). */
    suspend fun activeProfile(): ProfileEntity? {
        val id = _activeId.value
        return if (id == 0) null else db.profiles().get(id)
    }

    /**
     * Crea un perfil y lo activa. Si es el **primer** perfil del dispositivo
     * reclama los datos legacy (`owner = 0`): quien tenía favoritos y
     * progreso antes de F11 los conserva como su perfil.
     */
    suspend fun create(name: String): ProfileEntity {
        val now = System.currentTimeMillis()
        val isFirst = db.profiles().count() == 0
        val id = db.profiles().insert(
            ProfileEntity(
                id = 0,
                name = name.trim(),
                createdAt = now,
                lastUsedAt = now,
            ),
        ).toInt()
        if (isFirst) {
            db.favorites().claimLegacy(id)
            db.progress().claimLegacy(id)
        }
        activate(id)
        return db.profiles().get(id)!!
    }

    /** Cambia el perfil activo (persistido; los Flows re-emiten solos). */
    suspend fun activate(id: Int) {
        db.profiles().touch(id, System.currentTimeMillis())
        _activeId.value = id
        prefs.edit().putInt(KEY_ACTIVE, id).apply()
    }

    /**
     * Elimina un perfil y SOLO sus datos locales. El espacio remoto queda
     * huérfano en Supabase (aceptable: es barato y no hay API para borrarlo).
     *
     * Si era el activo, se activa el siguiente (o se vuelve a modo legacy
     * `0` si no queda ninguno): sin esto la biblioteca quedaría "vacía" en
     * pantalla porque `owner = 0` no tiene datos.
     */
    suspend fun delete(id: Int) {
        db.favorites().deleteForOwner(id)
        db.progress().deleteForOwner(id)
        db.profiles().delete(id)
        if (_activeId.value == id) {
            val next = db.profiles().first()
            if (next != null) {
                activate(next.id)
            } else {
                _activeId.value = 0
                prefs.edit().remove(KEY_ACTIVE).apply()
            }
        }
    }

    /** Asigna un sync space al perfil (lo que devuelve `sync_create`/`sync_join`). */
    suspend fun setSpace(id: Int, code: String, token: String) {
        db.profiles().setSpace(id, code, token)
    }

    /** Quita el sync space del perfil (Desvincular). */
    suspend fun clearSpace(id: Int) {
        db.profiles().clearSpace(id)
    }

    companion object {
        /** Nombre de las SharedPreferences (también las lee [AppLocale]). */
        const val PREFS = "jk-profiles"
        private const val KEY_ACTIVE = "active_profile_id"
    }
}
