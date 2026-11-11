package com.macastil.tv.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.macastil.tv.model.AnimeCard
import com.macastil.tv.ui.theme.JkOrange
import com.macastil.tv.ui.theme.JkSurfaceHigh
import com.macastil.tv.ui.theme.JkTextPrimary
import com.macastil.tv.ui.theme.JkTextSecondary

/**
 * Tarjeta vertical 2:3 con el póster del anime.
 * El foco se gestiona con [MutableInteractionSource] para no depender de
 * indicaciones de Material (en TV el feedback es escala + borde).
 *
 * `onLongClick` (mantener pulsado OK en el D-pad) se usa para marcar o quitar
 * favoritos sin entrar al detalle.
 */
@Composable
fun PosterCard(
    title: String,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    /** Color del badge: naranja por defecto; verde para "al día". */
    badgeColor: Color = JkOrange,
    subtitle: String? = null,
    /** null = gris. Se usa para pintar "En emisión" / "Concluido". */
    subtitleColor: Color? = null,
    /** 1 línea en las grillas de búsqueda para que las filas no salten. */
    titleMaxLines: Int = 2,
    /** null = ocupa el ancho del contenedor (grillas). */
    width: Dp? = 168.dp,
    focusRequester: FocusRequester? = null,
    onLongClick: (() -> Unit)? = null,
    isFavorite: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    // `tween` en vez del spring por defecto: con rebote el zoom del póster
    // se "pasaba" de 1.06 y volvía, y en movimiento se notaba como un pulso.
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = tween(durationMillis = 110),
        label = "posterScale",
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) JkOrange else Color.Transparent,
        animationSpec = tween(durationMillis = 110),
        label = "posterBorder",
    )

    val sized = if (width != null) modifier.width(width) else modifier.fillMaxWidth()

    Column(modifier = sized) {
        // El foco y el tamaño de layout van en el Box EXTERIOR, que nunca
        // cambia de tamaño. La escala del foco se aplica a una capa visual
        // interna: si el nodo enfocado creciera, Compose pediría
        // `bringIntoView` y la grilla entera se desplazaría unos píxeles en
        // vertical (ese era el "temblor" al recorrer las tarjetas).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                // El FocusRequester debe ir ANTES del modificador focusable
                // (clickable): si va después, no encuentra focus target y
                // requestFocus() devuelve false sin lanzar excepción.
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
                )
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onLongClick = onLongClick,
                    onClick = onClick,
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(scale)
                    .clip(RoundedCornerShape(10.dp))
                    .background(JkSurfaceHigh)
                    .border(
                        width = if (focused) 3.dp else 0.dp,
                        color = borderColor,
                        shape = RoundedCornerShape(10.dp),
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
                            .background(badgeColor)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }

                if (isFavorite) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xCC141414))
                            .padding(5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = "Favorite",
                            tint = JkOrange,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = if (focused) JkTextPrimary else JkTextSecondary,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
        )

        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = subtitleColor ?: JkTextSecondary,
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
