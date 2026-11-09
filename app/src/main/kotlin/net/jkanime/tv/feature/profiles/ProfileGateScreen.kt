package net.jkanime.tv.feature.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import net.jkanime.tv.Graph
import net.jkanime.tv.data.local.ProfileEntity
import net.jkanime.tv.ui.component.TvActionButton
import net.jkanime.tv.ui.component.TvIconButton
import net.jkanime.tv.ui.theme.JkBackground
import net.jkanime.tv.ui.theme.JkOrange
import net.jkanime.tv.ui.theme.JkRed
import net.jkanime.tv.ui.theme.JkSurface
import net.jkanime.tv.ui.theme.JkSurfaceHigh
import net.jkanime.tv.ui.theme.JkTextPrimary
import net.jkanime.tv.ui.theme.JkTextSecondary

/**
 * Puerta de entrada tipo Netflix (F11): se muestra al arrancar la app
 * cuando NO hay perfil configurado (`active_profile_id = 0`), y también se
 * reutiliza para **cambiar/crear/eliminar** perfiles una vez dentro de la
 * app (F12): a diferencia del menú de Home, esto exige salir de Home y
 * volver a esta pantalla de tarjetas (`startInChooser = true`).
 *
 * Reglas de la puerta inicial (`startInChooser = false`):
 *  - 0 perfiles  → formulario de creación (el primero reclama los datos
 *    legacy `owner=0` y, por el camino del sync, el espacio del dispositivo).
 *  - 1 perfil    → se activa solo (queda por default) y se entra a Home.
 *  - 2+ perfiles → selector para elegir, como Netflix.
 *
 * En modo reingreso (`startInChooser = true`) siempre se arranca en el
 * selector (aunque haya un solo perfil, porque la intención explícita es
 * verlo en tarjetas para crear/eliminar) y `BACK`/`onBack` devuelve a Home
 * sin tocar el perfil activo.
 */
@Composable
fun ProfileGateScreen(
    onReady: () -> Unit,
    startInChooser: Boolean = false,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val manager = remember(context) { Graph.profiles(context) }
    val scope = rememberCoroutineScope()

    // null = cargando (Room aún no emitió); después llega la lista real.
    val profiles by manager.profiles.collectAsStateWithLifecycle(initialValue = null)

    var mode by remember { mutableStateOf(if (startInChooser) Mode.CHOOSER else Mode.LOADING) }
    var name by remember { mutableStateOf("") }

    // Modo edición (borrar perfiles): solo tiene sentido dentro del
    // selector, así que se resetea cada vez que se entra/sale de él.
    var editMode by remember(mode) { mutableStateOf(false) }
    var confirmDeleteId by remember(mode) { mutableStateOf<Int?>(null) }

    val firstProfileFocus = remember { FocusRequester() }
    val nameFieldFocus = remember { FocusRequester() }
    val createButtonFocus = remember { FocusRequester() }
    val editToggleFocus = remember { FocusRequester() }

    // Decisión inicial según cuántos perfiles haya (solo desde LOADING, es
    // decir solo en la puerta de arranque, nunca en el reingreso).
    LaunchedEffect(profiles) {
        val list = profiles ?: return@LaunchedEffect
        when {
            mode != Mode.LOADING -> Unit
            list.isEmpty() -> mode = Mode.CREATE
            list.size == 1 -> {
                // Único perfil: queda por default sin preguntar.
                manager.activate(list.first().id)
                Graph.syncInBackground(context)
                onReady()
            }

            else -> mode = Mode.CHOOSER
        }
    }

    // Si se borra el último perfil desde el selector, no queda nada que
    // elegir: hay que pasar a creación igual que en una instalación nueva.
    LaunchedEffect(mode, profiles?.isEmpty()) {
        if (mode == Mode.CHOOSER && profiles?.isEmpty() == true) {
            mode = Mode.CREATE
        }
    }

    // BACK: en edición/creación vuelve un paso atrás; en el selector del
    // reingreso vuelve a Home sin tocar el perfil activo.
    BackHandler(enabled = mode == Mode.CREATE && !profiles.isNullOrEmpty()) { mode = Mode.CHOOSER }
    BackHandler(enabled = startInChooser && mode == Mode.CHOOSER) { onBack() }

    // Foco por modo: al primer perfil en el selector, al campo en creación
    // (el IME se abre solo y eso aquí está bien: es la única tarea). También
    // se re-dispara si la lista cambia de tamaño estando en el selector
    // (p. ej. tras borrar un perfil).
    LaunchedEffect(mode, profiles?.size) {
        val target = when (mode) {
            Mode.CHOOSER -> firstProfileFocus
            Mode.CREATE -> nameFieldFocus
            else -> return@LaunchedEffect
        }
        repeat(10) {
            withFrameNanos { }
            if (target.requestFocus()) return@LaunchedEffect
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JkBackground)
            .padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (mode) {
            Mode.LOADING -> Unit // pantalla negra un frame mientras Room emite

            Mode.CHOOSER -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = "¿Quién está viendo?",
                            style = MaterialTheme.typography.headlineMedium,
                            color = JkTextPrimary,
                        )
                        if (!profiles.isNullOrEmpty()) {
                            TvIconButton(
                                icon = if (editMode) Icons.Filled.Check else Icons.Filled.Edit,
                                contentDescription = if (editMode) {
                                    "Terminar edición"
                                } else {
                                    "Administrar perfiles"
                                },
                                active = editMode,
                                focusRequester = editToggleFocus,
                                onClick = {
                                    editMode = !editMode
                                    confirmDeleteId = null
                                },
                            )
                        }
                    }

                    val list = profiles.orEmpty()
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        list.forEachIndexed { index, profile ->
                            ProfileTile(
                                profile = profile,
                                focusRequester = if (index == 0) firstProfileFocus else null,
                                editMode = editMode,
                                confirmDelete = confirmDeleteId == profile.id,
                                onClick = {
                                    if (editMode) {
                                        confirmDeleteId = profile.id
                                    } else {
                                        scope.launch {
                                            manager.activate(profile.id)
                                            Graph.syncInBackground(context)
                                            onReady()
                                        }
                                    }
                                },
                                onConfirmDelete = {
                                    scope.launch {
                                        manager.delete(profile.id)
                                        confirmDeleteId = null
                                    }
                                },
                                onCancelDelete = { confirmDeleteId = null },
                            )
                        }
                        if (!editMode) {
                            NewProfileTile(
                                onClick = { mode = Mode.CREATE },
                            )
                        }
                    }

                    Text(
                        text = if (editMode) {
                            "Elige un perfil para eliminarlo."
                        } else {
                            "Cada perfil tiene sus favoritos, su progreso y su nube propios."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = JkTextSecondary,
                    )
                }
            }

            Mode.CREATE -> {
                // Formulario en una Surface MD3 (tv-material3): eleva el
                // bloque de creación del fondo con el mismo lenguaje visual
                // de las Card de los tiles, en vez del Box+background suelto
                // de antes.
                Surface(
                    modifier = Modifier.fillMaxWidth(0.6f),
                    shape = RoundedCornerShape(16.dp),
                    colors = SurfaceDefaults.colors(
                        containerColor = JkSurface,
                        contentColor = JkTextPrimary,
                    ),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                    ) {
                        Text(
                            text = if (profiles.isNullOrEmpty()) {
                                "Crea tu perfil para empezar"
                            } else {
                                "Crear perfil"
                            },
                            style = MaterialTheme.typography.headlineMedium,
                            color = JkOrange,
                        )
                        if (profiles.isNullOrEmpty()) {
                            Text(
                                text = "El primer perfil tomará tus favoritos y progreso actuales.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = JkTextSecondary,
                            )
                        }

                        BasicTextField(
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(nameFieldFocus)
                                // ▼/▲ salen del campo (el BasicTextField se
                                // traga las flechas para mover el cursor).
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) {
                                        return@onPreviewKeyEvent false
                                    }
                                    when (event.key) {
                                        Key.DirectionDown -> {
                                            val ok = runCatching { createButtonFocus.requestFocus() }
                                                .getOrDefault(false)
                                            if (!ok) runCatching { nameFieldFocus.requestFocus() }
                                            true
                                        }

                                        Key.DirectionUp -> {
                                            if (!profiles.isNullOrEmpty()) {
                                                mode = Mode.CHOOSER
                                            }
                                            true
                                        }

                                        else -> false
                                    }
                                },
                            textStyle = TextStyle(color = JkTextPrimary, fontSize = 18.sp),
                            cursorBrush = SolidColor(JkOrange),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (name.isNotBlank()) {
                                    scope.launch {
                                        manager.create(name)
                                        Graph.syncInBackground(context)
                                        onReady()
                                    }
                                }
                            }),
                            decorationBox = { inner ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(JkSurfaceHigh, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                ) {
                                    if (name.isEmpty()) {
                                        Text(
                                            text = "Nombre del perfil",
                                            color = JkTextSecondary,
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvActionButton(
                                text = "Crear perfil",
                                onClick = {
                                    scope.launch {
                                        manager.create(name)
                                        Graph.syncInBackground(context)
                                        onReady()
                                    }
                                },
                                enabled = name.isNotBlank(),
                                modifier = Modifier.focusRequester(createButtonFocus),
                            )
                            if (!profiles.isNullOrEmpty()) {
                                TvActionButton(
                                    text = "Cancelar",
                                    primary = false,
                                    onClick = { mode = Mode.CHOOSER },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class Mode { LOADING, CHOOSER, CREATE }

/**
 * Tile de perfil estilo Netflix, ahora con `androidx.tv.material3.Card`
 * (Material Design 3 para TV): `CardDefaults.colors/scale/shape/border`
 * reemplazan el `Box` + `background` + `border` manuales de antes. El
 * `Card` recibe `onClick`/`interactionSource` directamente, así que ya no
 * hace falta `.clickable(...)` — pero el `focusRequester()` sigue yendo
 * ANTES del `Card` en la cadena de modificadores (misma trampa F11: si va
 * después, `requestFocus()` falla en silencio).
 *
 * En `editMode` la tarjeta muestra una `X` roja y, al pulsarla, pide
 * confirmación (`confirmDelete`) con dos botones en vez de navegar al perfil.
 */
@Composable
private fun ProfileTile(
    profile: ProfileEntity,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    editMode: Boolean = false,
    confirmDelete: Boolean = false,
    onConfirmDelete: () -> Unit = {},
    onCancelDelete: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Card(
                onClick = onClick,
                modifier = Modifier
                    .then(
                        focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
                    )
                    .size(160.dp),
                interactionSource = interactionSource,
                shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
                colors = CardDefaults.colors(
                    containerColor = JkSurface,
                    contentColor = JkTextPrimary,
                    focusedContainerColor = JkSurfaceHigh,
                    focusedContentColor = JkOrange,
                ),
                scale = CardDefaults.scale(focusedScale = 1.05f),
                border = CardDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(3.dp, JkOrange),
                        shape = RoundedCornerShape(12.dp),
                    ),
                ),
                glow = CardDefaults.glow(focusedGlow = Glow(elevationColor = JkOrange, elevation = 8.dp)),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ProfileAvatar(
                        name = profile.name,
                        profileId = profile.id,
                        size = 88.dp,
                    )
                }
            }
            if (editMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        // El Card con Glow eleva su propio contenido y
                        // puede pintarse por encima de hermanos del mismo
                        // Box; sin zIndex la X roja queda tapada por la
                        // tarjeta (visible en bounds pero sin color real).
                        .zIndex(1f)
                        .padding(6.dp)
                        .size(28.dp)
                        .background(JkRed, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Eliminar perfil",
                        tint = Color(0xFF141414),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (confirmDelete) {
            Text(
                text = "¿Eliminar \"${profile.name}\"?",
                style = MaterialTheme.typography.labelSmall,
                color = JkTextPrimary,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvActionButton(text = "Eliminar", primary = false, onClick = onConfirmDelete)
                TvActionButton(text = "Cancelar", primary = false, onClick = onCancelDelete)
            }
        } else {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (focused) JkOrange else JkTextSecondary,
            )
        }
    }
}

/** Tile "+ Nuevo perfil" al final del selector, con el mismo estilo Card. */
@Composable
private fun NewProfileTile(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Card(
            onClick = onClick,
            modifier = Modifier.size(160.dp),
            interactionSource = interactionSource,
            shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
            colors = CardDefaults.colors(
                containerColor = JkSurface,
                contentColor = JkTextSecondary,
                focusedContainerColor = JkSurfaceHigh,
                focusedContentColor = JkOrange,
            ),
            scale = CardDefaults.scale(focusedScale = 1.05f),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(3.dp, JkOrange),
                    shape = RoundedCornerShape(12.dp),
                ),
            ),
            glow = CardDefaults.glow(focusedGlow = Glow(elevationColor = JkOrange, elevation = 8.dp)),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (focused) JkOrange else JkTextSecondary,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Nuevo perfil",
            style = MaterialTheme.typography.bodyLarge,
            color = if (focused) JkOrange else JkTextSecondary,
        )
    }
}
