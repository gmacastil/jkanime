package net.jkanime.tv.feature.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import net.jkanime.tv.Graph
import net.jkanime.tv.data.sync.PairingCode
import net.jkanime.tv.data.sync.SyncCredentials
import net.jkanime.tv.data.sync.SyncResult
import net.jkanime.tv.ui.component.TvActionButton
import net.jkanime.tv.ui.theme.JkBackground
import net.jkanime.tv.ui.theme.JkOrange
import net.jkanime.tv.ui.theme.JkSurfaceHigh
import net.jkanime.tv.ui.theme.JkTextPrimary
import net.jkanime.tv.ui.theme.JkTextSecondary

/**
 * Pantalla de sincronización multi-dispositivo.
 *
 * Flujo: en el primer dispositivo se crea un "sync space" y se muestra un
 * código de 8 dígitos. En los demás se introduce ese código con el teclado
 * numérico en pantalla (no hay teclado físico ni Play Services en TV).
 *
 * El token se intercambia por debajo; el usuario solo ve y escribe el código.
 */
@Composable
fun SyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val engine = remember(context) { Graph.sync(context) }
    val scope = rememberCoroutineScope()

    // `currentCredentials()` es suspend ahora: lee el perfil activo de Room
    // (multiusuario F11). Se resuelve al entrar; si hay espacio, el modo
    // pasa a PAIRED en el mismo frame.
    var credentials by remember { mutableStateOf<SyncCredentials?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var typedCode by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf<PairingCode?>(null) }
    var mode by remember { mutableStateOf(Mode.CHOOSE) }

    LaunchedEffect(Unit) {
        val current = engine.currentCredentials()
        credentials = current
        if (current != null) mode = Mode.PAIRED
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(mode) {
        repeat(10) {
            withFrameNanos { }
            if (firstFocus.requestFocus()) return@LaunchedEffect
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JkBackground)
            .padding(32.dp),
    ) {
        Text(
            text = "Sincronización",
            style = MaterialTheme.typography.headlineSmall,
            color = JkTextPrimary,
        )
        Text(
            text = "Comparte tus favoritos y el progreso entre tus TV y tu celular.",
            style = MaterialTheme.typography.bodyMedium,
            color = JkTextSecondary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (!engine.isConfigured) {
            InfoBox(
                "Falta configurar el backend.\n" +
                    "Define JK_SUPABASE_URL y JK_SUPABASE_ANON_KEY en gradle.properties " +
                    "y vuelve a compilar.",
            )
            Spacer(modifier = Modifier.height(20.dp))
            TvActionButton("Volver", primary = false, onClick = onBack)
            return@Column
        }

        when (mode) {
            Mode.CHOOSE -> ChooseMode(
                busy = busy,
                focusRequester = firstFocus,
                onCreate = {
                    busy = true
                    status = null
                    scope.launch {
                        runCatching { engine.createSpace() }
                            .onSuccess {
                                credentials = it
                                mode = Mode.PAIRED
                                status = "Espacio creado"
                            }
                            .onFailure { status = "Error: ${it.message}" }
                        busy = false
                    }
                },
                onJoin = { mode = Mode.JOIN },
                onBack = onBack,
            )

            Mode.JOIN -> JoinForm(
                code = typedCode,
                busy = busy,
                status = status,
                onCodeChange = { typedCode = it },
                onConfirm = {
                    busy = true
                    status = null
                    scope.launch {
                        runCatching { engine.joinSpace(typedCode) }
                            .onSuccess {
                                credentials = engine.currentCredentials()
                                mode = Mode.PAIRED
                                status = "Dispositivo vinculado"
                            }
                            .onFailure { status = "No se pudo vincular: ${it.message}" }
                        busy = false
                    }
                },
                onBack = { mode = Mode.CHOOSE },
            )

            Mode.PAIRED -> PairedView(
                code = credentials?.code.orEmpty(),
                busy = busy,
                status = status,
                focusRequester = firstFocus,
                onSyncNow = {
                    android.util.Log.i("JkSyncUI", "onSyncNow: click")
                    busy = true
                    status = null
                    scope.launch {
                        status = when (val result = engine.syncNow()) {
                            is SyncResult.Success ->
                                "Sincronizado: ${result.favorites} favoritos, ${result.progress} progresos"
                            is SyncResult.Failure -> "Error: ${result.message}"
                            SyncResult.NotPaired -> "Este dispositivo no está vinculado"
                            SyncResult.NotConfigured -> "Backend sin configurar"
                        }
                        busy = false
                    }
                },
                pairing = pairing,
                onGeneratePairing = {
                    android.util.Log.i("JkSyncUI", "onGeneratePairing: click")
                    busy = true
                    status = null
                    scope.launch {
                        runCatching { engine.createPairingCode() }
                            .onSuccess {
                                android.util.Log.i("JkSyncUI", "pairing ok: ${it.code}")
                                pairing = it
                            }
                            .onFailure {
                                android.util.Log.w("JkSyncUI", "pairing fallo", it)
                                status = "Error: ${it.message}"
                            }
                        busy = false
                    }
                },
                onUnlink = {
                    scope.launch {
                        engine.clearSpace()
                        credentials = null
                        mode = Mode.CHOOSE
                        status = null
                    }
                },
                onBack = onBack,
            )
        }
    }
}

private enum class Mode { CHOOSE, JOIN, PAIRED }

@Composable
private fun ChooseMode(
    busy: Boolean,
    focusRequester: FocusRequester,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onBack: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "¿Este es el primer dispositivo?",
            style = MaterialTheme.typography.titleSmall,
            color = JkTextPrimary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvActionButton(
                text = "Crear espacio de sync",
                primary = true,
                enabled = !busy,
                modifier = Modifier.focusRequester(focusRequester),
                onClick = onCreate,
            )
            TvActionButton(
                text = "Ya tengo un código",
                primary = false,
                enabled = !busy,
                onClick = onJoin,
            )
            TvActionButton(text = "Volver", primary = false, onClick = onBack)
        }
    }
}

@Composable
private fun PairedView(
    code: String,
    busy: Boolean,
    status: String?,
    focusRequester: FocusRequester,
    pairing: PairingCode?,
    onGeneratePairing: () -> Unit,
    onSyncNow: () -> Unit,
    onUnlink: () -> Unit,
    onBack: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Código de sincronización",
            style = MaterialTheme.typography.titleSmall,
            color = JkTextSecondary,
        )
        Text(
            text = code.chunked(4).joinToString("  "),
            style = MaterialTheme.typography.displaySmall,
            color = JkOrange,
        )
        Text(
            text = "Este es el identificador del espacio. Para vincular un " +
                "dispositivo nuevo necesitas un código temporal.",
            style = MaterialTheme.typography.bodySmall,
            color = JkTextSecondary,
        )

        pairing?.let { current ->
            val minutesLeft =
                ((current.expiresAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Código para un dispositivo nuevo (caduca en $minutesLeft min)",
                    style = MaterialTheme.typography.labelMedium,
                    color = JkTextSecondary,
                )
                Text(
                    text = current.code.chunked(4).joinToString("  "),
                    style = MaterialTheme.typography.headlineMedium,
                    color = JkTextPrimary,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvActionButton(
                text = if (busy) "Sincronizando…" else "Sincronizar ahora",
                primary = true,
                enabled = !busy,
                modifier = Modifier.focusRequester(focusRequester),
                onClick = onSyncNow,
            )
            TvActionButton(
                text = if (pairing == null) "Vincular otro dispositivo" else "Generar otro código",
                primary = false,
                enabled = !busy,
                onClick = onGeneratePairing,
            )
            TvActionButton(text = "Desvincular", primary = false, onClick = onUnlink)
            TvActionButton(text = "Volver", primary = false, onClick = onBack)
        }

        status?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = JkTextPrimary)
        }
    }
}

/**
 * Formulario de vinculación. El código se escribe con un teclado numérico
 * navegable con D-pad; el token se pega/escribe aparte porque es largo.
 */
@Composable
private fun JoinForm(
    code: String,
    busy: Boolean,
    status: String?,
    onCodeChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "Código (8 dígitos)",
            style = MaterialTheme.typography.titleSmall,
            color = JkTextSecondary,
        )
        Text(
            text = code.padEnd(8, '·').chunked(4).joinToString("  "),
            style = MaterialTheme.typography.headlineSmall,
            color = JkOrange,
        )

        Keypad(
            onDigit = { if (code.length < 8) onCodeChange(code + it) },
            onBackspace = { onCodeChange(code.dropLast(1)) },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvActionButton(
                text = if (busy) "Vinculando…" else "Vincular",
                primary = true,
                enabled = !busy && code.length == 8,
                onClick = onConfirm,
            )
            TvActionButton(text = "Atrás", primary = false, onClick = onBack)
        }

        status?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = JkTextPrimary)
        }
    }
}

@Composable
private fun Keypad(onDigit: (String) -> Unit, onBackspace: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("⌫", "0", ""),
        ).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    when (key) {
                        "" -> Spacer(modifier = Modifier.width(72.dp))
                        "⌫" -> TvActionButton(
                            text = key,
                            primary = false,
                            onClick = onBackspace,
                        )
                        else -> TvActionButton(
                            text = key,
                            primary = false,
                            onClick = { onDigit(key) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoBox(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .clip(RoundedCornerShape(10.dp))
            .background(JkSurfaceHigh)
            .padding(20.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = JkTextPrimary,
            textAlign = TextAlign.Start,
        )
    }
}
