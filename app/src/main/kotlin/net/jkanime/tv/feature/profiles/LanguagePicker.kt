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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import net.jkanime.tv.R
import net.jkanime.tv.data.local.AppLocale
import net.jkanime.tv.ui.theme.JkOrange
import net.jkanime.tv.ui.theme.JkSurface
import net.jkanime.tv.ui.theme.JkTextPrimary
import net.jkanime.tv.ui.theme.JkTextSecondary

/**
 * Selector de idioma de la app ("idioma por defecto" del perfil).
 *
 * Tres opciones — Sistema (default) / Español / Inglés — como filas siempre
 * visibles con check, en el mismo lenguaje visual de los filtros del
 * Directorio (fondo naranja al enfocar, tinte naranja si está seleccionada).
 *
 * Dos formas de usarlo:
 * - [LanguageOptions] a secas, para el cajón del avatar (el foco inicial lo
 *   gestiona quien lo monta, vía `focusRequester` sobre la primera fila).
 * - [LanguagePickerDialog], ventana modal (la puerta de perfiles).
 *
 * El cambio se aplica recreando la activity: [AppLocale.wrap] en
 * `MainActivity.attachBaseContext` es quien realmente aplica el locale.
 */
@Composable
fun LanguageOptions(
    current: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Si lo traen, la primera fila lo expone (lo enfoca el cajón). */
    focusRequester: FocusRequester? = null,
) {
    val options = listOf(
        AppLocale.SYSTEM to R.string.lang_system,
        AppLocale.ES to R.string.lang_es,
        AppLocale.EN to R.string.lang_en,
    )
    val rowFocus = remember { List(options.size) { FocusRequester() } }
    // El requester externo (cajón) se engancha a la fila de la opción
    // actual: el `LaunchedEffect` de quien lo monta enfoca directamente la
    // elección vigente en vez de la primera de la lista.
    val selectedIndex = options.indexOfFirst { it.first == current }.coerceAtLeast(0)

    // Sin gestor externo (diálogo): enfocar la opción actual al abrir.
    if (focusRequester == null) {
        LaunchedEffect(current) {
            repeat(10) { withFrameNanos { } }
            repeat(8) {
                if (rowFocus[selectedIndex].requestFocus()) return@LaunchedEffect
                withFrameNanos { }
            }
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, (code, labelRes) ->
            LanguageRow(
                label = stringResource(labelRes),
                selected = code == current,
                focusRequester = rowFocus[index],
                extraFocusRequester = if (index == selectedIndex) focusRequester else null,
                onSelect = { onSelect(code) },
            )
        }
    }
}

@Composable
private fun LanguageRow(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester,
    extraFocusRequester: FocusRequester?,
    onSelect: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val background = when {
        focused -> JkOrange
        selected -> JkOrange.copy(alpha = 0.2f)
        else -> Color.Transparent
    }
    // Con el fondo naranja completo el tinte naranja sería ilegible: al
    // enfocar pasa a tinta oscura (mismo contraste que TvIconButton).
    val contentColor = when {
        focused -> Color(0xFF141414)
        selected -> JkOrange
        else -> JkTextPrimary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Los dos requesters van antes del clickable (foco antes que foco-objeto).
            .focusRequester(focusRequester)
            .then(
                if (extraFocusRequester != null) {
                    Modifier.focusRequester(extraFocusRequester)
                } else {
                    Modifier
                },
            )
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelect,
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
        )
    }
}

/**
 * Ventana modal con el selector de idioma (mismo patrón que
 * `DeleteProfileDialog`: velo a pantalla completa para confinar el D-pad).
 * Tras elegir, el llamador cierra y recrea la activity.
 */
@Composable
fun LanguagePickerDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.82f)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.width(440.dp),
                shape = RoundedCornerShape(16.dp),
                colors = SurfaceDefaults.colors(
                    containerColor = JkSurface,
                    contentColor = JkTextPrimary,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.lang_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = JkTextPrimary,
                    )
                    Text(
                        text = stringResource(R.string.lang_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = JkTextSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LanguageOptions(
                        current = current,
                        onSelect = onSelect,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
