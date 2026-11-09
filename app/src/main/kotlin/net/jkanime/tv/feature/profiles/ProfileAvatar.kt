package net.jkanime.tv.feature.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.jkanime.tv.ui.theme.JkOrange
import net.jkanime.tv.ui.theme.JkTextSecondary

/**
 * Paleta fija para distinguir perfiles a simple vista (estilo Netflix): cada
 * perfil recibe un color determinista por su `id` (no aleatorio ni elegible
 * todavía), así el avatar no cambia de color entre sesiones ni dispositivos.
 */
private val AvatarPalette = listOf(
    JkOrange,
    Color(0xFF3DDC84),
    Color(0xFF4F9DFF),
    Color(0xFFB36BFF),
    Color(0xFFFF6BA6),
    Color(0xFFFFC14D),
)

/** Color determinista para el avatar de un perfil (id=0 = modo legacy/sin perfil). */
fun avatarColorFor(profileId: Int): Color {
    if (profileId <= 0) return JkTextSecondary
    val index = profileId % AvatarPalette.size
    return AvatarPalette[index]
}

/**
 * Avatar circular reutilizado tanto en el selector de perfiles
 * (`ProfileGateScreen`) como en la cabecera de Home: inicial del nombre
 * sobre un color fijo por perfil.
 */
@Composable
fun ProfileAvatar(
    name: String,
    profileId: Int,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarColorFor(profileId)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1).uppercase().ifEmpty { "?" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF141414),
        )
    }
}
