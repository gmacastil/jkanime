package com.macastil.mobile.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macastil.mobile.JkGraph
import com.macastil.mobile.data.sync.PairingCode
import com.macastil.mobile.data.sync.SyncResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SyncUiState(
    val code: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val pairing: PairingCode? = null,
    val lastSync: String? = null,
)

/**
 * Estado de la pantalla de sincronización.
 *
 * El espacio de sync es el mismo que usa la app TV (RPC de
 * `docs/supabase/schema.sql`): se puede compartir biblioteca entre televisor
 * y teléfono vinculando el código de emparejamiento.
 */
class SyncViewModel : ViewModel() {

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(code = JkGraph.library.getCredentials()?.code)
        }
    }

    fun createSpace() = run("Creando espacio…") {
        val credentials = JkGraph.syncEngine.createSpace()
        _state.value = _state.value.copy(code = credentials.code, message = "Espacio creado")
    }

    fun newPairingCode() = run("Generando código…") {
        val pairing = JkGraph.syncEngine.createPairingCode()
        _state.value = _state.value.copy(
            pairing = pairing,
            message = "Código válido 15 minutos y de un solo uso",
        )
    }

    fun join(code: String) = run("Vinculando…") {
        JkGraph.syncEngine.joinSpace(code.trim())
        val credentials = JkGraph.library.getCredentials()
        _state.value = _state.value.copy(
            code = credentials?.code,
            message = "Dispositivo vinculado",
        )
    }

    fun syncNow() = run("Sincronizando…") {
        when (val result = JkGraph.syncEngine.syncNow()) {
            is SyncResult.Success -> _state.value = _state.value.copy(
                lastSync = "${result.favorites} favoritos · ${result.progress} progresos",
                message = "Sincronizado",
            )
            SyncResult.NotPaired -> _state.value = _state.value.copy(
                message = "Primero crea o vincula un espacio",
            )
            SyncResult.NotConfigured -> _state.value = _state.value.copy(
                message = "Falta configurar Supabase en local.properties",
            )
            is SyncResult.Failure -> _state.value = _state.value.copy(
                message = "Error: ${result.message}",
            )
        }
    }

    fun clearSpace() = run("Desvinculando…") {
        JkGraph.syncEngine.clearSpace()
        _state.value = _state.value.copy(code = null, pairing = null, message = "Espacio desvinculado")
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null, pairing = null)
    }

    private fun run(initialMessage: String, block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, message = initialMessage)
            try {
                block()
            } catch (error: Exception) {
                _state.value = _state.value.copy(message = error.message ?: "Error")
            }
            _state.value = _state.value.copy(busy = false)
        }
    }
}
