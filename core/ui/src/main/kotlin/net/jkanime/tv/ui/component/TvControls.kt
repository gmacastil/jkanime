package net.jkanime.tv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.tv.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.jkanime.tv.ui.theme.JkGreen
import net.jkanime.tv.ui.theme.JkOrange
import net.jkanime.tv.ui.theme.JkSurface
import net.jkanime.tv.ui.theme.JkSurfaceHigh
import net.jkanime.tv.ui.theme.JkTextPrimary
import net.jkanime.tv.ui.theme.JkTextSecondary

/** Botón pensado para D-pad: sin ripple, feedback por borde + color. */
@Composable
fun TvActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val background = when {
        !enabled -> JkSurface
        focused && primary -> JkOrange
        focused -> JkSurfaceHigh
        primary -> JkOrange
        else -> JkSurfaceHigh
    }
    val textColor = when {
        !enabled -> JkTextSecondary
        focused && primary -> Color(0xFF141414)
        primary -> Color(0xFF141414)
        else -> JkTextPrimary
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(
                width = if (focused && !primary) 2.dp else 0.dp,
                color = if (focused && !primary) JkOrange else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
        )
    }
}

/**
 * Botón compacto de icono para D-pad: mismo lenguaje visual que
 * [TvActionButton] pero cuadrado y sin texto. Se usa para el menú
 * hamburguesa, la lupa del buscador y el icono de ordenamiento.
 */
@Composable
fun TvIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    focusRequester: FocusRequester? = null,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    tint: Color? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val background = when {
        focused -> JkSurfaceHigh
        active -> JkOrange
        else -> JkSurface
    }
    val contentColor = tint ?: when {
        focused -> JkOrange
        active -> Color(0xFF141414)
        else -> JkTextPrimary
    }

    Box(
        modifier = modifier
            // El FocusRequester va antes del focusable (clickable).
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) JkOrange else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(iconSize),
        )
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
            .clickable(
                interactionSource = interactionSource,
                indication = null,
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
            watched -> "Visto"
            inProgress -> progressLabel ?: "En curso"
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
