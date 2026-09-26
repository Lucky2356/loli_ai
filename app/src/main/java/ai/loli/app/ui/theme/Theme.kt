package ai.loli.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.loli.app.settings.AccentColor
import ai.loli.app.settings.ThemeMode
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/** Акцент — спокойный индиго; второй цвет (бирюза) — только для голоса и прогресса. */
val Accent = Color(0xFF6366F1)
val AccentSoft = Color(0xFF8B8DFF)
val Mint = Color(0xFF2DD4BF)
val Coral = Color(0xFFFF6B6B)

private val Light = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E9FF),
    onPrimaryContainer = Color(0xFF1F1F66),
    secondary = Color(0xFF5F5F6B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEDF2),
    onSecondaryContainer = Color(0xFF1B1B21),
    tertiary = Color(0xFF0D9488),
    onTertiary = Color.White,
    background = Color(0xFFF8F8FA),
    onBackground = Color(0xFF111114),
    surface = Color(0xFFF8F8FA),
    onSurface = Color(0xFF111114),
    surfaceVariant = Color(0xFFEFEFF3),
    onSurfaceVariant = Color(0xFF6B6B76),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF1F1F5),
    surfaceContainerHigh = Color(0xFFEAEAF0),
    surfaceContainerHighest = Color(0xFFE3E3EA),
    outline = Color(0xFFD9D9E0),
    outlineVariant = Color(0xFFEAEAEF),
    error = Color(0xFFDC3545),
    onError = Color.White,
    errorContainer = Color(0xFFFFE8EA),
    onErrorContainer = Color(0xFF6B0F1A),
    inverseSurface = Color(0xFF1C1C21),
    inverseOnSurface = Color(0xFFF2F2F5),
)

private val Dark = darkColorScheme(
    primary = AccentSoft,
    onPrimary = Color(0xFF0E0E33),
    primaryContainer = Color(0xFF26264D),
    onPrimaryContainer = Color(0xFFDEDEFF),
    secondary = Color(0xFFA1A1AD),
    onSecondary = Color(0xFF16161B),
    secondaryContainer = Color(0xFF232329),
    onSecondaryContainer = Color(0xFFE6E6EC),
    tertiary = Mint,
    onTertiary = Color(0xFF00201C),
    background = Color(0xFF0A0A0D),
    onBackground = Color(0xFFF1F1F4),
    surface = Color(0xFF0A0A0D),
    onSurface = Color(0xFFF1F1F4),
    surfaceVariant = Color(0xFF1C1C22),
    onSurfaceVariant = Color(0xFF9B9BA7),
    surfaceContainerLowest = Color(0xFF060608),
    surfaceContainerLow = Color(0xFF111115),
    surfaceContainer = Color(0xFF15151A),
    surfaceContainerHigh = Color(0xFF1C1C22),
    surfaceContainerHighest = Color(0xFF24242B),
    outline = Color(0xFF2E2E36),
    outlineVariant = Color(0xFF212128),
    error = Coral,
    onError = Color(0xFF3A0006),
    errorContainer = Color(0xFF3A1417),
    onErrorContainer = Color(0xFFFFDADB),
    inverseSurface = Color(0xFFF1F1F4),
    inverseOnSurface = Color(0xFF16161B),
)

private val base = Typography()

private val LoliTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 17.sp),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

private val LoliShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun LoliTheme(mode: ThemeMode = ThemeMode.SYSTEM, dynamic: Boolean = false, accent: AccentColor = AccentColor.INDIGO, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> withAccent(if (dark) Dark else Light, accent, dark)
    }
    // Сфера всегда в «чистом» выбранном цвете (белая Лоли остаётся белой и в светлой теме).
    val orb = if (dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || accent == AccentColor.INDIGO) null else Color(accent.dark)
    androidx.compose.runtime.CompositionLocalProvider(LocalOrbColor provides orb) {
        MaterialTheme(colorScheme = scheme, typography = LoliTypography, shapes = LoliShapes, content = content)
    }
}

/** Основной цвет сферы Лоли; null — акцент темы. */
val LocalOrbColor = androidx.compose.runtime.staticCompositionLocalOf<Color?> { null }

/** Цвет, выбранный пользователем: акцент, контейнеры и второй цвет сферы (tertiary). */
private fun withAccent(base: ColorScheme, accent: AccentColor, dark: Boolean): ColorScheme {
    if (accent == AccentColor.INDIGO) return base
    val primary = Color(if (dark) accent.dark else accent.light)
    val glow = Color(accent.glow)
    val onPrimary = if (primary.luminance() > 0.45f) Color(0xFF111114) else Color.White
    val container = primary.copy(alpha = if (dark) 0.22f else 0.14f).compositeOver(base.background)
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = container,
        onPrimaryContainer = if (dark) primary.copy(alpha = 0.95f).compositeOver(Color.White) else primary.copy(alpha = 0.9f).compositeOver(Color.Black),
        tertiary = glow,
        onTertiary = if (glow.luminance() > 0.45f) Color(0xFF111114) else Color.White,
    )
}
