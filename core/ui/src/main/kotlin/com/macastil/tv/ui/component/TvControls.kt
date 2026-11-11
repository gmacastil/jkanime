package com.macastil.tv.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Check
import androidx.tv.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.macastil.tv.ui.R
import com.macastil.tv.ui.theme.JkGreen
import com.macastil.tv.ui.theme.JkRed
import com.macastil.tv.ui.theme.JkOrange
import com.macastil.tv.ui.theme.JkOrangeBright
import com.macastil.tv.ui.theme.JkSurface
import com.macastil.tv.ui.theme.JkSurfaceHigh
import com.macastil.tv.ui.theme.JkTextPrimary
import com.macastil.tv.ui.theme.JkTextSecondary

/** Botón de acción pensado para D-pad: sin ripple, feedback por borde + color. */
@Composable
fun TvActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    danger: Boolean = false,
    compact: Boolean = false,
    icon: ImageVector? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val accent = if (danger) JkRed else JkOrange
    val filled = primary || danger

    val background = when {
        !enabled -> JkSurface
        danger -> if (focused) JkRed.copy(alpha = 0.88f) else JkRed
        primary -> if (focused) JkOrangeBright else JkOrange
        focused -> JkSurfaceHigh
        else -> JkSurface
    }
    // Los primarios no llevan borde en reposo; al enfocar se les pone uno
    // claro para que el foco se lea sobre el naranja (antes no cambiaban).
    val borderColor = when {
        !enabled -> JkSurfaceHigh.copy(alpha = 0.6f)
        focused && filled -> JkTextPrimary
        focused -> accent
        filled -> Color.Transparent
        else -> JkSurfaceHigh
    }
    val borderWidth = when {
        !enabled -> 1.dp
        focused -> 2.dp
        filled -> 0.dp
        else -> 1.dp
    }
    val contentColor = when {
        !enabled -> JkTextSecondary.copy(alpha = 0.45f)
        filled -> Color(0xFF141414)
        focused -> accent
        else -> JkTextPrimary
    }
    val shape = RoundedCornerShape(if (compact) 10.dp else 12.dp)

    Row(
        modifier = modifier
            .heightIn(min = if (compact) 42.dp else 52.dp)
            .scale(if (focused) 1.04f else 1f)
            .clip(shape)
            .background(background)
            .border(borderWidth, borderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(
                horizontal = if (compact) 16.dp else 26.dp,
                vertical = if (compact) 8.dp else 14.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = text,
            style = if (compact) {
                MaterialTheme.typography.labelLarge
            } else {
                MaterialTheme.typography.titleSmall
            },
            color = contentColor,
        )
    }
}

/**
 * Botón compacto de icono para D-pad: mismo lenguaje visual que
 * [TvActionButton] (borde tenue en reposo, borde naranja al enfocar) pero
 * cuadrado y sin texto. Se usa en la cabecera, el paginado y el orden.
 */
@Composable
fun TvIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    size: Dp = 48.dp,
    iconSize: Dp = 22.dp,
    tint: Color? = null,
) {
    TvIconButtonBox(
        onClick = onClick,
        modifier = modifier,
        active = active,
        enabled = enabled,
        focusRequester = focusRequester,
        size = size,
        tint = tint,
    ) { contentColor ->
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * Sobrecarga para iconos gráficos (drawables de la app, p. ej. el logo
 * `caiman_home`) en vez de un [ImageVector]: mismo comportamiento de foco
 * y estados, solo cambia qué se pinta dentro. `circle = true` lo deja
 * como círculo.
 */
@Composable
fun TvIconButton(
    painter: Painter,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    size: Dp = 48.dp,
    imageSize: Dp = 30.dp,
    /** true = contenedor e imagen en círculo (logo de la app, p. ej. el caimán). */
    circle: Boolean = false,
) {
    TvIconButtonBox(
        onClick = onClick,
        modifier = modifier,
        active = active,
        enabled = enabled,
        focusRequester = focusRequester,
        size = size,
        tint = null,
        circle = circle,
    ) {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(imageSize)
                .clip(if (circle) CircleShape else RoundedCornerShape(6.dp)),
        )
    }
}

/** Contenedor (borde/fondo/foco) común a las dos sobrecargas de [TvIconButton]. */
@Composable
private fun TvIconButtonBox(
    onClick: () -> Unit,
    modifier: Modifier,
    active: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester?,
    size: Dp,
    tint: Color?,
    circle: Boolean = false,
    content: @Composable (Color) -> Unit,
) {
    val shape = if (circle) CircleShape else RoundedCornerShape(12.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val background = when {
        !enabled -> JkSurface.copy(alpha = 0.6f)
        focused -> JkSurfaceHigh
        active -> JkOrange
        else -> JkSurface
    }
    val contentColor = tint ?: when {
        !enabled -> JkTextSecondary.copy(alpha = 0.4f)
        focused -> JkOrange
        active -> Color(0xFF141414)
        else -> JkTextPrimary
    }
    val borderColor = when {
        !enabled -> JkSurfaceHigh.copy(alpha = 0.5f)
        focused -> JkOrange
        active -> Color.Transparent
        else -> JkSurfaceHigh
    }

    Box(
        modifier = modifier
            // El FocusRequester va antes del focusable (clickable).
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
            .size(size)
            .clip(shape)
            .background(background)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = borderColor,
                shape = shape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content(contentColor)
    }
}

/**
 * Chip-botón para el paginado: número de página seleccionable. `selected`
 * lo pinta naranja (página actual) y el foco lo marca con borde.
 */
@Composable
fun TvChipButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val background = when {
        selected -> if (focused) JkOrangeBright else JkOrange
        focused -> JkSurfaceHigh
        else -> JkSurface
    }
    val contentColor = when {
        selected -> Color(0xFF141414)
        focused -> JkOrange
        else -> JkTextPrimary
    }
    val borderColor = when {
        focused && selected -> JkTextPrimary
        focused -> JkOrange
        selected -> Color.Transparent
        else -> JkSurfaceHigh
    }

    Box(
        modifier = modifier
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
            .size(width = 52.dp, height = 42.dp)
            .scale(if (focused) 1.06f else 1f)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
    }
}

/**
 * Teclado numérico de 4 filas compartido por el sync (menú del avatar, alta de
 * perfil) y el salto de página del Directorio. Solo pinta las teclas: las
 * acciones (confirmar / volver) las pone quien lo usa.
 */
@Composable
fun TvNumberPad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onConfirm: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("⌫", "0", ""),
        ).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
                row.forEachIndexed { colIndex, key ->
                    val isFirst = rowIndex == 0 && colIndex == 0
                    val cellWidth = if (compact) 58.dp else 70.dp
                    val mod = Modifier
                        .width(cellWidth)
                        .then(
                            if (isFirst && focusRequester != null) {
                                Modifier.focusRequester(focusRequester)
                            } else {
                                Modifier
                            },
                        )
                    when (key) {
                        "" -> if (onConfirm == null) {
                            Spacer(modifier = mod)
                        } else {
                            Box(
                                modifier = mod,
                                contentAlignment = Alignment.Center,
                            ) {
                                TvIconButton(
                                    icon = Icons.Filled.Check,
                                    contentDescription = stringResource(R.string.keypad_link_cd),
                                    size = if (compact) 42.dp else 48.dp,
                                    iconSize = if (compact) 22.dp else 24.dp,
                                    onClick = onConfirm,
                                )
                            }
                        }
                        "⌫" -> TvActionButton(
                            text = key,
                            primary = false,
                            compact = true,
                            enabled = enabled,
                            modifier = mod,
                            onClick = onBackspace,
                        )
                        else -> TvActionButton(
                            text = key,
                            primary = false,
                            compact = true,
                            enabled = enabled,
                            modifier = mod,
                            onClick = { onDigit(key) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Teclado compacto para la clave de recuperación. Usa un alfabeto base32 sin
 * caracteres ambiguos (I/O/0/1), para que una clave dictada o copiada a mano
 * no se confunda en la TV.
 */
@Composable
fun TvRecoveryPad(
    onChar: (String) -> Unit,
    onBackspace: () -> Unit,
    onConfirm: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    val rows = listOf(
        listOf("A", "B", "C", "D", "E", "F", "G", "H"),
        listOf("J", "K", "L", "M", "N", "P", "Q", "R"),
        listOf("S", "T", "U", "V", "W", "X", "Y", "Z"),
        listOf("2", "3", "4", "5", "6", "7", "8", "9"),
        // 0/1 se conservan para poder introducir claves numéricas creadas por
        // la primera versión de esta función; las nuevas no los generan.
        listOf("0", "1", "⌫", "", "", "", "", "✓"),
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEachIndexed { colIndex, key ->
                    val isFirst = rowIndex == 0 && colIndex == 0
                    val cell = Modifier
                        .width(42.dp)
                        .then(if (isFirst && focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    when {
                        key == "" -> Spacer(modifier = cell)
                        key == "⌫" -> TvActionButton(
                            text = key,
                            primary = false,
                            compact = true,
                            enabled = enabled,
                            modifier = cell,
                            onClick = onBackspace,
                        )
                        key == "✓" -> Box(cell, contentAlignment = Alignment.Center) {
                            TvIconButton(
                                icon = Icons.Filled.Check,
                                contentDescription = stringResource(R.string.keypad_link_cd),
                                size = 42.dp,
                                iconSize = 22.dp,
                                onClick = onConfirm ?: {},
                            )
                        }
                        else -> TvActionButton(
                            text = key,
                            primary = false,
                            compact = true,
                            enabled = enabled,
                            modifier = cell,
                            onClick = { onChar(key) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Flechas arriba/abajo apiladas (el `⇅` de ordenar). No existe en
 * `material-icons-core` (solo en el artefacto `extended`, que pesa decenas de
 * MB), así que se compone con los dos chevrons que sí vienen en core.
 */
@Composable
fun SortArrowsIcon(
    modifier: Modifier = Modifier,
    tint: Color = JkTextPrimary,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy((-8).dp),
    ) {
        Icon(
            imageVector = Icons.Filled.KeyboardArrowUp,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Fila de capítulo (columna derecha del detalle).
 *
 * Tres estados visuales:
 * - **visto** (`watched`): insignia y etiqueta en verde.
 * - **en curso** (`inProgress`): insignia naranja + barra fina de avance.
 * - **sin ver**: insignia neutra.
 */
@Composable
fun ChapterRow(
    number: String,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    watched: Boolean = false,
    inProgress: Boolean = false,
    progressFraction: Float = 0f,
    progressLabel: String? = null,
    focusRequester: FocusRequester? = null,
    /** Mantener OK: marca/desmarca el capítulo como visto. */
    onLongClick: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val background = when {
        focused -> JkSurfaceHigh
        watched -> JkGreen.copy(alpha = 0.12f)
        else -> JkSurface
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) JkOrange else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            // El FocusRequester va antes del focusable (clickable).
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(
                    when {
                        watched -> JkGreen
                        inProgress -> JkOrange
                        else -> JkSurfaceHigh
                    },
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelLarge,
                color = if (watched || inProgress) Color(0xFF141414) else JkTextPrimary,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = JkTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (inProgress && !watched) {
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(JkSurfaceHigh),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(JkOrange),
                    )
                }
            }
        }

        val label = when {
            watched -> stringResource(R.string.chapter_watched)
            inProgress -> progressLabel ?: stringResource(R.string.chapter_in_progress)
            else -> null
        }
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (watched) JkGreen else JkOrange,
            )
        }
    }
}

/** Encabezado de sección simple. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = JkTextPrimary,
        modifier = modifier.padding(start = 24.dp, top = 12.dp, bottom = 8.dp),
    )
}

/** Chip informativo (géneros, tipo, estado). */
@Composable
fun InfoChip(text: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (highlight) JkOrange else JkSurfaceHigh)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlight) Color(0xFF141414) else JkTextSecondary,
        )
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .background(JkSurfaceHigh)
            .padding(top = 1.dp),
    )
}

/**
 * Color del estado de emisión tal como lo publica el sitio:
 * **naranja** mientras se emite, **verde** cuando ya concluyó.
 *
 * Se resuelve por texto porque las tarjetas de búsqueda y del Directorio solo
 * traen la etiqueta ya renderizada (`En emisión`, `Concluido`, …), no el enum.
 */
fun airingStatusColor(label: String?): Color {
    val value = label?.lowercase() ?: return JkTextSecondary
    return when {
        "emisi" in value || "emit" in value -> JkOrange
        "conclu" in value || "finaliz" in value || "termin" in value -> JkGreen
        else -> JkTextSecondary
    }
}
