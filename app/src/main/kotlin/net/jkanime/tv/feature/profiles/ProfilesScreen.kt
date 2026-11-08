package net.jkanime.tv.feature.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.jkanime.tv.Graph
import net.jkanime.tv.ui.component.TvActionButton
import net.jkanime.tv.ui.theme.JkBackground
import net.jkanime.tv.ui.theme.JkOrange
import net.jkanime.tv.ui.theme.JkSurface
import net.jkanime.tv.ui.theme.JkSurfaceHigh
import net.jkanime.tv.ui.theme.JkTextPrimary
import net.jkanime.tv.ui.theme.JkTextSecondary

/**
 * Perfiles locales estilo Netflix (F11 multiusuario).
 *
 * Cada perfil es un dueño de su biblioteca en Room y de su sync space en la
 * nube. Cambiar de perfil en esta pantalla re-emiten todos los Flows de
 * `LibraryRepository` (flatMapLatest sobre el owner activo): la Home, el
 * detalle y el sync pasan a mostrar los datos del nuevo dueño sin recargar.
 *
 * El primer perfil que se crea reclama los datos legacy (`owner = 0`) y el
 * espacio del dispositivo, así nadie pierde sus favoritos al actualizar.
 */
@Composable
fun ProfilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { Graph.profiles(context) }
    val scope = rememberCoroutineScope()

    val profiles by manager.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeId by manager.activeId.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf("") }
    var confirmDeleteId by remember { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    val firstRowFocus = remember { FocusRequester() }
    val nameFieldFocus = remember { FocusRequester() }
    val createButtonFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Foco inicial: a la primera fila si hay perfiles, al campo si no (al
    // enfocar el campo el IME se abre solo, así que al volver a la lista hay
    // que ocultarlo a mano: si queda abierto, el D-pad se lo come el teclado).
    LaunchedEffect(profiles.isEmpty()) {
        val target = if (profiles.isEmpty()) nameFieldFocus else firstRowFocus
        android.util.Log.i("JkProfiles", "entry effect empty=${profiles.isEmpty()}")
        repeat(10) {
            withFrameNanos { }
            val ok = target.requestFocus()
            if (ok) {
                android.util.Log.i("JkProfiles", "focused target row=$target imeHidden")
                if (target == firstRowFocus) keyboard?.hide()
                return@LaunchedEffect
            }
        }
        android.util.Log.w("JkProfiles", "no se pudo enfocar el objetivo")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JkBackground)
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Perfiles",
            style = MaterialTheme.typography.headlineSmall,
            color = JkOrange,
        )
        Text(
            text = "Cada perfil tiene sus favoritos, su progreso y su nube propios.",
            style = MaterialTheme.typography.bodyMedium,
            color = JkTextSecondary,
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (profiles.isEmpty()) {
            Text(
                text = "Sin perfiles todavía. El primero tomará tus datos actuales.",
                style = MaterialTheme.typography.bodyMedium,
                color = JkTextSecondary,
            )
        }

        profiles.forEachIndexed { index, profile ->
            val interactionSource = remember { MutableInteractionSource() }
            val focused by interactionSource.collectIsFocusedAsState()
            val isActive = profile.id == activeId

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        // Antes de clickable: focusRequester debe preceder al
                        // focus target, si no requestFocus() devuelve false.
                        .then(
                            if (index == 0) Modifier.focusRequester(firstRowFocus)
                            else Modifier,
                        )
                        .background(
                            color = if (focused) JkSurfaceHigh else JkSurface,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                        ) {
                            scope.launch {
                                manager.activate(profile.id)
                                status = "Perfil activo: ${profile.name}"
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = JkTextPrimary,
                        )
                        if (isActive) {
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "ACTIVO",
                                style = MaterialTheme.typography.labelSmall,
                                color = JkOrange,
                                letterSpacing = 1.sp,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                if (confirmDeleteId == profile.id) {
                    TvActionButton(
                        text = "Eliminar",
                        primary = false,
                        onClick = {
                            scope.launch {
                                manager.delete(profile.id)
                                confirmDeleteId = null
                                status = "Perfil eliminado"
                            }
                        },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TvActionButton(
                        text = "Cancelar",
                        primary = false,
                        onClick = { confirmDeleteId = null },
                    )
                } else {
                    TvActionButton(
                        text = "Eliminar",
                        primary = false,
                        onClick = { confirmDeleteId = profile.id },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Crear perfil",
            style = MaterialTheme.typography.titleMedium,
            color = JkTextPrimary,
        )

        BasicTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(nameFieldFocus)
                // ▼ sale del campo al botón Crear (o a la lista si el botón
                // está deshabilitado); ▲ vuelve a la lista. Sin esto el
                // BasicTextField se traga DirectionUp/DirectionDown para
                // mover el cursor y el D-pad queda atrapado en el campo.
                .onPreviewKeyEvent { event ->
                    android.util.Log.i(
                        "JkProfiles",
                        "preview key=${event.key} type=${event.type}",
                    )
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            val ok = runCatching { createButtonFocus.requestFocus() }
                                .getOrDefault(false)
                            val ok2 = if (!ok) {
                                runCatching { firstRowFocus.requestFocus() }
                                    .getOrDefault(false)
                            } else {
                                true
                            }
                            android.util.Log.i("JkProfiles", "DOWN ok=$ok fallback=$ok2")
                            true
                        }

                        Key.DirectionUp -> {
                            val ok = runCatching { firstRowFocus.requestFocus() }
                                .getOrDefault(false)
                            android.util.Log.i("JkProfiles", "UP rowFocus=$ok")
                            keyboard?.hide()
                            true
                        }

                        else -> false
                    }
                },
            textStyle = TextStyle(color = JkTextPrimary, fontSize = 18.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(JkOrange),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (name.isNotBlank()) {
                    scope.launch {
                        manager.create(name)
                        name = ""
                        status = "Perfil creado"
                    }
                }
            }),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(JkSurface, RoundedCornerShape(8.dp))
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
                    if (name.isNotBlank()) {
                        scope.launch {
                            manager.create(name)
                            name = ""
                            status = "Perfil creado"
                        }
                    }
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.focusRequester(createButtonFocus),
            )
            TvActionButton(text = "Volver", primary = false, onClick = onBack)
        }

        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = JkTextSecondary,
            )
        }
    }
}
