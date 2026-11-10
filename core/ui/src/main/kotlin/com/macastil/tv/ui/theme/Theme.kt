package com.macastil.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val JkOrange = Color(0xFFFF6B35)

/** Variante clara de [JkOrange] para el estado de foco de botones primarios. */
val JkOrangeBright = Color(0xFFFF8A5C)
val JkBackground = Color(0xFF0E0E11)
val JkSurface = Color(0xFF17171C)
val JkSurfaceHigh = Color(0xFF24242C)
val JkTextPrimary = Color(0xFFF4F4F6)
val JkTextSecondary = Color(0xFFA6A6B2)

/** Capítulos ya vistos (verde) y estado "en emisión". */
val JkGreen = Color(0xFF3DDC84)

/** Acciones destructivas (eliminar perfil). */
val JkRed = Color(0xFFE84C4C)

private val JkColorScheme = darkColorScheme(
    primary = JkOrange,
    onPrimary = Color(0xFF141414),
    background = JkBackground,
    onBackground = JkTextPrimary,
    surface = JkSurface,
    onSurface = JkTextPrimary,
)

@Composable
fun JkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = JkColorScheme, content = content)
}
