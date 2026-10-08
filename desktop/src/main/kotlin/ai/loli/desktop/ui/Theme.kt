package ai.loli.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Палитра Лоли: спокойный графит + фирменный градиент индиго → фиолет. */
@Immutable
data class LoliPalette(
    val dark: Boolean,
    val background: Color,
    val sidebar: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceHover: Color,
    val outline: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val accent2: Color,
    val onAccent: Color,
    val userBubble: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
) {
    val gradient: Brush get() = Brush.linearGradient(listOf(accent, accent2))
}

val DarkPalette = LoliPalette(
    dark = true,
    background = Color(0xFF0D0E12),
    sidebar = Color(0xFF111217),
    surface = Color(0xFF16171D),
    surfaceHigh = Color(0xFF1C1E26),
    surfaceHover = Color(0xFF23252F),
    outline = Color(0xFF2A2D38),
    text = Color(0xFFEDEEF3),
    muted = Color(0xFF9AA0AE),
    faint = Color(0xFF636978),
    accent = Color(0xFF7C7BFF),
    accent2 = Color(0xFFB46BFF),
    onAccent = Color.White,
    userBubble = Color(0xFF2B2A5C),
    success = Color(0xFF3DD68C),
    warning = Color(0xFFFFB547),
    danger = Color(0xFFFF6B6B),
)

val LightPalette = LoliPalette(
    dark = false,
    background = Color(0xFFF6F7FB),
    sidebar = Color(0xFFEEF0F6),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFF3F4F9),
    surfaceHover = Color(0xFFE9EBF3),
    outline = Color(0xFFE1E4EC),
    text = Color(0xFF15161C),
    muted = Color(0xFF626978),
    faint = Color(0xFF9097A6),
    accent = Color(0xFF5B57F5),
    accent2 = Color(0xFF9B4DF5),
    onAccent = Color.White,
    userBubble = Color(0xFFE6E4FF),
    success = Color(0xFF16A36A),
    warning = Color(0xFFD48806),
    danger = Color(0xFFE5484D),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Текущая палитра. */
val palette: LoliPalette @Composable get() = LocalPalette.current

private fun typography(p: LoliPalette): Typography {
    val base = TextStyle(color = p.text)
    return Typography(
        displaySmall = base.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 38.sp, letterSpacing = (-0.4).sp),
        headlineSmall = base.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
        titleLarge = base.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
        titleMedium = base.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
        titleSmall = base.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp),
        bodyLarge = base.copy(fontSize = 15.sp, lineHeight = 23.sp),
        bodyMedium = base.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = base.copy(fontSize = 12.sp, lineHeight = 17.sp, color = p.muted),
        labelLarge = base.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
        labelMedium = base.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = p.muted),
        labelSmall = base.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = p.faint, letterSpacing = 0.4.sp),
    )
}

@Composable
fun LoliTheme(dark: Boolean, content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val scheme = remember(dark) { if (dark) darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent, secondary = p.accent2, background = p.background, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.muted, outline = p.outline,
        outlineVariant = p.outline, error = p.danger, surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceHigh,
        surfaceContainerHighest = p.surfaceHover, surfaceContainerLow = p.surface, primaryContainer = p.userBubble, onPrimaryContainer = p.text,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = p.onAccent, secondary = p.accent2, background = p.background, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.muted, outline = p.outline,
        outlineVariant = p.outline, error = p.danger, surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceHigh,
        surfaceContainerHighest = p.surfaceHover, surfaceContainerLow = p.surface, primaryContainer = p.userBubble, onPrimaryContainer = p.text,
    ) }
    val type = remember(p) { typography(p) }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
