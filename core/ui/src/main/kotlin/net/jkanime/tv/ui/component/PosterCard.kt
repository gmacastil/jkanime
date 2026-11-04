package net.jkanime.tv.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import net.jkanime.tv.model.AnimeCard
import net.jkanime.tv.ui.theme.JkOrange
import net.jkanime.tv.ui.theme.JkSurfaceHigh
import net.jkanime.tv.ui.theme.JkTextPrimary
import net.jkanime.tv.ui.theme.JkTextSecondary

/**
 * Tarjeta vertical 2:3 con el póster del anime.
 * El foco se gestiona con [MutableInteractionSource] para no depender de
 * indicaciones de Material (en TV el feedback es escala + borde).
 */
@Composable
fun PosterCard(
    title: String,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    subtitle: String? = null,
    /** null = ocupa el ancho del contenedor (grillas). */
    width: Dp? = 168.dp,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "posterScale")
    val borderColor by animateColorAsState(
        if (focused) JkOrange else Color.Transparent,
        label = "posterBorder",
    )

    val sized = if (width != null) modifier.width(width) else modifier.fillMaxWidth()

    Column(modifier = sized) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .scale(scale)
                .clip(RoundedCornerShape(10.dp))
                .background(JkSurfaceHigh)
                .border(
                    width = if (focused) 3.dp else 0.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(10.dp),
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
                ),
        ) {
            AsyncImage(
                model = posterUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            if (badge != null) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF141414),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(JkOrange)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = if (focused) JkTextPrimary else JkTextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
        )

        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = JkTextSecondary,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp, start = 2.dp),
            )
        }
    }
}

/** Fila horizontal con título, usada en el home. */
@Composable
fun AnimeCardRow(
    title: String,
    cards: List<AnimeCard>,
    onCardClick: (AnimeCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cards.isEmpty()) return

    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = JkTextPrimary,
            modifier = Modifier.padding(start = 24.dp, bottom = 10.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(cards, key = { it.episodeUrl }) { card ->
                PosterCard(
                    title = card.title,
                    posterUrl = card.posterUrl ?: card.thumbUrl,
                    badge = card.episodeNumber?.let { "Ep $it" },
                    subtitle = card.dateLabel,
                    onClick = { onCardClick(card) },
                )
            }
        }
    }
}
