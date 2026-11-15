package com.macastil.mobile.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macastil.mobile.R
import com.macastil.mobile.data.sync.PairingCode
import com.macastil.tv.model.formatCode
import com.macastil.tv.model.isCompleteRecoveryKey
import com.macastil.tv.model.normalizePairingCode
import com.macastil.tv.model.normalizeRecoveryKey
import com.macastil.tv.ui.theme.JkGreen
import com.macastil.tv.ui.theme.JkOrange
import com.macastil.tv.ui.theme.JkTextSecondary

/**
 * Panel abierto dentro de la tarjeta de sync.
 *
 * Son los mismos modos que el cajón del avatar de la TV, pero como formularios
 * en línea (en el teléfono hay teclado, así que no hace falta el teclado
 * numérico en pantalla del D-pad).
 */
private enum class SyncPanel { NONE, JOIN, RECOVER, SET_RECOVERY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onBack: () -> Unit = {},
) {
    val viewModel: SyncViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var panel by remember { mutableStateOf(SyncPanel.NONE) }
    var joinCode by remember { mutableStateOf("") }
    var recoveryKey by remember { mutableStateOf("") }

    // Al guardar la clave el formulario se cierra solo y queda la nota verde.
    LaunchedEffect(state.recoverySaved) {
        if (state.recoverySaved) {
            panel = SyncPanel.NONE
            recoveryKey = ""
        }
    }

    // Crear el espacio lleva directo a definir la clave, igual que en la TV.
    val showRecoverySetup =
        panel == SyncPanel.SET_RECOVERY || (state.askRecoveryKey && state.code != null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_volver),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.sync_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = JkTextSecondary,
            )

            if (state.code == null) {
                UnlinkedCard(
                    busy = state.busy,
                    panel = panel,
                    joinCode = joinCode,
                    recoveryKey = recoveryKey,
                    onOpenPanel = { opened ->
                        panel = if (panel == opened) SyncPanel.NONE else opened
                        recoveryKey = ""
                    },
                    onJoinCodeChange = { joinCode = normalizePairingCode(it) },
                    onRecoveryKeyChange = { recoveryKey = normalizeRecoveryKey(it) },
                    onCreate = viewModel::createSpace,
                    onJoin = { viewModel.join(joinCode) },
                    onRecover = { viewModel.recover(recoveryKey) },
                )
            } else {
                LinkedCard(
                    code = state.code.orEmpty(),
                    busy = state.busy,
                    lastSync = state.lastSync,
                    pairing = state.pairing,
                    showRecoverySetup = showRecoverySetup,
                    recoverySaved = state.recoverySaved,
                    recoveryKey = recoveryKey,
                    onOpenRecoverySetup = {
                        panel = if (showRecoverySetup) SyncPanel.NONE else SyncPanel.SET_RECOVERY
                        recoveryKey = ""
                    },
                    onRecoveryKeyChange = { recoveryKey = normalizeRecoveryKey(it) },
                    onSyncNow = viewModel::syncNow,
                    onGeneratePairing = viewModel::newPairingCode,
                    onSaveRecoveryKey = { viewModel.setRecoveryKey(recoveryKey) },
                    onSkipRecoveryKey = {
                        panel = SyncPanel.NONE
                        recoveryKey = ""
                        viewModel.skipRecoveryKey()
                    },
                    onUnlink = {
                        panel = SyncPanel.NONE
                        joinCode = ""
                        recoveryKey = ""
                        viewModel.clearSpace()
                    },
                )
            }

            MessageRow(message = state.message, busy = state.busy)

            // Ayuda: qué es cada credencial (en la TV lo explica el propio menú).
            Text(
                text = stringResource(R.string.sync_help),
                style = MaterialTheme.typography.bodySmall,
                color = JkTextSecondary.copy(alpha = 0.8f),
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Sin espacio: crear, unirse con código temporal o recuperar con clave. */
@Composable
private fun UnlinkedCard(
    busy: Boolean,
    panel: SyncPanel,
    joinCode: String,
    recoveryKey: String,
    onOpenPanel: (SyncPanel) -> Unit,
    onJoinCodeChange: (String) -> Unit,
    onRecoveryKeyChange: (String) -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onRecover: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.sync_not_linked),
                style = MaterialTheme.typography.titleMedium,
            )

            Button(
                onClick = onCreate,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.sync_create))
            }

            OutlinedButton(
                onClick = { onOpenPanel(SyncPanel.JOIN) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.sync_have_code_button))
            }
            if (panel == SyncPanel.JOIN) {
                Text(
                    text = stringResource(R.string.sync_join_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = JkTextSecondary,
                )
                OutlinedTextField(
                    value = joinCode,
                    onValueChange = onJoinCodeChange,
                    label = { Text(stringResource(R.string.sync_code_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = onJoin,
                    enabled = !busy && joinCode.length == 8,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.sync_link_device))
                }
            }

            OutlinedButton(
                onClick = { onOpenPanel(SyncPanel.RECOVER) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.sync_recovery_join))
            }
            if (panel == SyncPanel.RECOVER) {
                RecoveryKeyField(
                    value = recoveryKey,
                    onValueChange = onRecoveryKeyChange,
                    hint = stringResource(R.string.sync_recovery_hint),
                )
                Text(
                    text = stringResource(R.string.sync_recovery_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = JkTextSecondary,
                )
                Button(
                    onClick = onRecover,
                    enabled = !busy && isCompleteRecoveryKey(recoveryKey),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.sync_recovery_recover))
                }
            }
        }
    }
}

/** Con espacio: sincronizar, generar código, definir clave y desvincular. */
@Composable
private fun LinkedCard(
    code: String,
    busy: Boolean,
    lastSync: Pair<Int, Int>?,
    pairing: PairingCode?,
    showRecoverySetup: Boolean,
    recoverySaved: Boolean,
    recoveryKey: String,
    onOpenRecoverySetup: () -> Unit,
    onRecoveryKeyChange: (String) -> Unit,
    onSyncNow: () -> Unit,
    onGeneratePairing: () -> Unit,
    onSaveRecoveryKey: () -> Unit,
    onSkipRecoveryKey: () -> Unit,
    onUnlink: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.sync_space_label),
                style = MaterialTheme.typography.labelLarge,
                color = JkTextSecondary,
            )
            Text(
                text = formatCode(code),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )

            Button(
                onClick = onSyncNow,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.sync_now))
            }

            lastSync?.let { (favorites, progress) ->
                Text(
                    text = stringResource(
                        R.string.sync_last,
                        stringResource(R.string.sync_msg_result, favorites, progress),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = JkGreen,
                )
            }

            OutlinedButton(
                onClick = onGeneratePairing,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.sync_generate))
            }

            if (pairing != null) {
                Text(
                    text = stringResource(R.string.sync_code, pairing.code),
                    style = MaterialTheme.typography.headlineSmall,
                    color = JkOrange,
                )
                Text(
                    text = stringResource(R.string.sync_code_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = JkTextSecondary,
                )
            }

            OutlinedButton(
                onClick = onOpenRecoverySetup,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.sync_recovery_set))
            }
            if (showRecoverySetup) {
                Text(
                    text = stringResource(R.string.sync_recovery_set_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = JkTextSecondary,
                )
                RecoveryKeyField(
                    value = recoveryKey,
                    onValueChange = onRecoveryKeyChange,
                    hint = stringResource(R.string.sync_recovery_hint),
                )
                Button(
                    onClick = onSaveRecoveryKey,
                    enabled = !busy && isCompleteRecoveryKey(recoveryKey),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.sync_recovery_save))
                }
                // Solo se puede omitir cuando el panel salió solo (espacio
                // recién creado); si el usuario lo abrió, se cierra con el botón.
                TextButton(
                    onClick = onSkipRecoveryKey,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.sync_recovery_skip))
                }
            } else if (recoverySaved) {
                Text(
                    text = stringResource(R.string.sync_recovery_saved_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = JkGreen,
                )
            }

            OutlinedButton(
                onClick = onUnlink,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.sync_unlink))
            }
        }
    }
}

/** Campo de la clave de 6 hex: solo A-F y dígitos, en mayúsculas. */
@Composable
private fun RecoveryKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(hint) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            capitalization = KeyboardCapitalization.Characters,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MessageRow(message: UiText?, busy: Boolean) {
    if (message == null) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Color.White)
        }
        Text(
            text = stringResource(message.res, *message.args.toTypedArray()),
            style = MaterialTheme.typography.bodyMedium,
            color = JkTextSecondary,
        )
    }
}
