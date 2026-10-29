package app.pendant.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BB8FF),
    onPrimary = Color(0xFF0A1830),
    secondary = Color(0xFF7FD9A6),
    onSecondary = Color(0xFF00391D),
    background = Color(0xFF10131A),
    onBackground = Color(0xFFE8EAF2),
    surface = Color(0xFF171B25),
    onSurface = Color(0xFFE8EAF2),
    surfaceVariant = Color(0xFF232838),
    onSurfaceVariant = Color(0xFFB9BFCF),
    error = Color(0xFFFF8A80),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B5CBA),
    onPrimary = Color.White,
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF171B25),
    surface = Color.White,
    onSurface = Color(0xFF171B25),
)

@Composable
fun PendantTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
