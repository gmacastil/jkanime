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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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

/** Item de la grilla de capítulos. */
@Composable
fun EpisodeTile(
    number: String,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    watched: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .width(260.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) JkSurfaceHigh else JkSurface)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) JkOrange else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (watched) JkSurfaceHigh else JkOrange)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelLarge,
                color = if (watched) JkTextSecondary else Color(0xFF141414),
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
            Text(
                text = if (watched) "Visto" else "Ver",
                style = MaterialTheme.typography.labelSmall,
                color = JkTextSecondary,
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
