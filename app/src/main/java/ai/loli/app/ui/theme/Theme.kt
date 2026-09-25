package ai.loli.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Violet = Color(0xFF8B7CFF)
val Cyan = Color(0xFF4FD8E8)
val Rose = Color(0xFFFF7AA8)
val DeepNight = Color(0xFF0D1020)
val NightSurface = Color(0xFF161A2E)
val NightSurfaceHigh = Color(0xFF1F2440)

private val DarkColors = darkColorScheme(
    primary = Violet,
    onPrimary = Color(0xFF140E3D),
    secondary = Cyan,
    onSecondary = Color(0xFF002A30),
    tertiary = Rose,
    background = DeepNight,
    onBackground = Color(0xFFE6E8F5),
    surface = NightSurface,
    onSurface = Color(0xFFE6E8F5),
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = Color(0xFFB4B8D6),
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceHigh,
    outline = Color(0xFF3A4066),
    error = Color(0xFFFF8A80),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B4BDB),
    onPrimary = Color.White,
    secondary = Color(0xFF00838F),
    tertiary = Color(0xFFD1467A),
    background = Color(0xFFF7F7FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFECEBF7),
    onSurfaceVariant = Color(0xFF4A4A63),
)

@Composable
fun LoliTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = Typography(), content = content)
}
