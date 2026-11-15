package com.macastil.mobile.ui.sync

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macastil.mobile.JkGraph
import com.macastil.mobile.R
import com.macastil.mobile.data.sync.PairingCode
import com.macastil.mobile.data.sync.SyncException
import com.macastil.mobile.data.sync.SyncResult
import com.macastil.tv.model.formatCode
import com.macastil.tv.model.normalizePairingCode
import com.macastil.tv.model.normalizeRecoveryKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Mensaje de la pantalla: recurso + argumentos, **no** texto ya formateado.
 *
 * Importante: el idioma lo elige el usuario en el perfil y lo aplica
 * `MainActivity.attachBaseContext` sobre el Context de la **activity**. Por eso
 * aquí no se puede usar `getString` (ni `AndroidViewModel`, que resuelve con el
 * Application y devolvía los avisos en el idioma del sistema, p. ej. "Space
 * created" en medio de una pantalla en español): la pantalla los formatea con
 * `stringResource`, que sí usa el Context de la activity.
 */
data class UiText(
    @StringRes val res: Int,
    val args: List<Any> = emptyList(),
)

data class SyncUiState(
    val code: String? = null,
    val busy: Boolean = false,
    val message: UiText? = null,
    val pairing: PairingCode? = null,
    /** Último sync: nº de favoritos y de progresos que llegaron. */
    val lastSync: Pair<Int, Int>? = null,
    /**
     * Tras crear el espacio se pide definir la clave de recuperación, igual que
     * en la TV: sin clave no se puede volver a entrar desde otro dispositivo si
     * se pierde el token.
     */
    val askRecoveryKey: Boolean = false,
    /** Clave de recuperación ya guardada (para mostrarla en el panel). */
    val recoverySaved: Boolean = false,
)

/**
 * Estado de la sincronización del perfil.
 *
 * Reproduce los modos de la app TV sobre el mismo esquema (`docs/supabase/schema.sql`):
 * - **Sin espacio**: crear, unirse con código temporal (8 dígitos) o recuperar
 *   con clave (6 hex).
 * - **Con espacio**: sincronizar ahora, generar código para otro dispositivo,
 *   definir clave de recuperación y desvincular.
 */
class SyncViewModel : ViewModel() {

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(code = JkGraph.library.getCredentials()?.code)
        }
    }

    fun createSpace() = run(R.string.sync_msg_creating) {
        val credentials = JkGraph.syncEngine.createSpace()
        _state.value = _state.value.copy(
            code = credentials.code,
            message = UiText(R.string.sync_msg_created, listOf(formatCode(credentials.code))),
            // Mismo paso que la TV: espacio creado → define la clave.
            askRecoveryKey = true,
        )
    }

    fun newPairingCode() = run(R.string.sync_msg_generating) {
        val pairing = JkGraph.syncEngine.createPairingCode()
        _state.value = _state.value.copy(
            pairing = pairing,
            message = UiText(R.string.sync_msg_code_ready),
        )
    }

    fun join(code: String) = run(R.string.sync_msg_creating) {
        JkGraph.syncEngine.joinSpace(normalizePairingCode(code))
        val credentials = JkGraph.library.getCredentials()
        _state.value = _state.value.copy(
            code = credentials?.code,
            pairing = null,
            askRecoveryKey = false,
            message = UiText(R.string.sync_msg_linked),
        )
    }

    /** Recupera el espacio en otro dispositivo a partir de la clave de 6 hex. */
    fun recover(recoveryKey: String) = run(R.string.sync_msg_recovering) {
        JkGraph.syncEngine.recoverSpace(normalizeRecoveryKey(recoveryKey))
        val credentials = JkGraph.library.getCredentials()
        _state.value = _state.value.copy(
            code = credentials?.code,
            pairing = null,
            askRecoveryKey = false,
            message = UiText(R.string.sync_msg_recovered),
        )
    }

    /** Define la clave con la que se podrá recuperar el espacio desde otro dispositivo. */
    fun setRecoveryKey(recoveryKey: String) = run(R.string.sync_msg_saving_key) {
        JkGraph.syncEngine.setRecoveryKey(normalizeRecoveryKey(recoveryKey))
        _state.value = _state.value.copy(
            askRecoveryKey = false,
            recoverySaved = true,
            message = UiText(R.string.sync_msg_recovery_saved),
        )
    }

    /** Descarta el paso de definir clave tras crear el espacio. */
    fun skipRecoveryKey() {
        _state.value = _state.value.copy(askRecoveryKey = false, message = null)
    }

    fun syncNow() = run(R.string.sync_now) {
        when (val result = JkGraph.syncEngine.syncNow()) {
            is SyncResult.Success -> _state.value = _state.value.copy(
                lastSync = result.favorites to result.progress,
                message = UiText(
                    R.string.sync_msg_result,
                    listOf(result.favorites, result.progress),
                ),
            )

            SyncResult.NotPaired -> _state.value = _state.value.copy(
                message = UiText(R.string.sync_msg_need_space),
            )

            SyncResult.NotConfigured -> _state.value = _state.value.copy(
                message = UiText(R.string.sync_msg_not_configured),
            )

            is SyncResult.Failure -> _state.value = _state.value.copy(
                message = result.error?.let { UiText(it.res ?: R.string.common_error, it.args) }
                    ?: UiText(R.string.common_error, listOf(result.message)),
            )
        }
    }

    fun clearSpace() = run(R.string.sync_unlink) {
        JkGraph.syncEngine.clearSpace()
        _state.value = _state.value.copy(
            code = null,
            pairing = null,
            lastSync = null,
            askRecoveryKey = false,
            recoverySaved = false,
            message = UiText(R.string.sync_msg_unlinked),
        )
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null, pairing = null)
    }

    private fun run(@StringRes initialMessageRes: Int, block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                busy = true,
                message = UiText(initialMessageRes),
            )
            try {
                block()
            } catch (error: Exception) {
                // `SyncException` trae el recurso; el resto, el mensaje crudo.
                _state.value = _state.value.copy(
                    message = if (error is SyncException && error.res != null) {
                        UiText(error.res, error.args)
                    } else {
                        UiText(R.string.common_error, listOf(error.message ?: ""))
                    },
                )
            }
            _state.value = _state.value.copy(busy = false)
        }
    }
}
