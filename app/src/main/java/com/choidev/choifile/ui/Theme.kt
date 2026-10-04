package com.choidev.choifile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens in one place. The look follows shadcn/ui: a neutral zinc palette, 1px borders instead of
 * tonal elevation, rounded-md corners, generous spacing and a quiet type scale with muted secondary text.
 * Material3 components pick these up through the mapped [ColorScheme], [Shapes] and [Typography];
 * custom pieces read [ChoiTheme.colors], [Space], [Radius] and [IconSize] directly.
 */
private object Zinc {
    val c50 = Color(0xFFFAFAFA)
    val c100 = Color(0xFFF4F4F5)
    val c200 = Color(0xFFE4E4E7)
    val c300 = Color(0xFFD4D4D8)
    val c400 = Color(0xFFA1A1AA)
    val c500 = Color(0xFF71717A)
    val c600 = Color(0xFF52525B)
    val c700 = Color(0xFF3F3F46)
    val c800 = Color(0xFF27272A)
    val c900 = Color(0xFF18181B)
    val c950 = Color(0xFF09090B)
}

/** Semantic colors with shadcn names. */
@Immutable
data class ChoiColors(
    val background: Color,
    val foreground: Color,
    val card: Color,
    val popover: Color,
    val primary: Color,
    val primaryForeground: Color,
    val secondary: Color,
    val secondaryForeground: Color,
    val muted: Color,
    val mutedForeground: Color,
    val accent: Color,
    val accentForeground: Color,
    val destructive: Color,
    val destructiveForeground: Color,
    val border: Color,
    val input: Color,
    val ring: Color,
    /** Selected rows and the active tab: a hair darker/lighter than [accent]. */
    val selection: Color,
    val scrim: Color,
    val success: Color,
    val dark: Boolean,
)

private val LightColors = ChoiColors(
    background = Color.White, foreground = Zinc.c950,
    card = Color.White, popover = Color.White,
    primary = Zinc.c900, primaryForeground = Zinc.c50,
    secondary = Zinc.c100, secondaryForeground = Zinc.c900,
    muted = Zinc.c100, mutedForeground = Zinc.c500,
    accent = Zinc.c100, accentForeground = Zinc.c900,
    destructive = Color(0xFFDC2626), destructiveForeground = Zinc.c50,
    border = Zinc.c200, input = Zinc.c200, ring = Zinc.c400,
    selection = Color(0xFFEDEDEF), scrim = Color(0x66000000), success = Color(0xFF16A34A),
    dark = false,
)

private val DarkColors = ChoiColors(
    background = Zinc.c950, foreground = Zinc.c50,
    card = Zinc.c950, popover = Color(0xFF0F0F11),
    primary = Zinc.c50, primaryForeground = Zinc.c900,
    secondary = Zinc.c800, secondaryForeground = Zinc.c50,
    muted = Zinc.c800, mutedForeground = Zinc.c400,
    accent = Zinc.c800, accentForeground = Zinc.c50,
    destructive = Color(0xFFEF4444), destructiveForeground = Zinc.c50,
    border = Zinc.c800, input = Zinc.c800, ring = Zinc.c600,
    selection = Color(0xFF1E1E21), scrim = Color(0x99000000), success = Color(0xFF22C55E),
    dark = true,
)

/** Spacing scale (4-pt grid). */
object Space {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

/** Corner radii: sm for chips and thumbnails, md (shadcn's default) for buttons and inputs, lg for dialogs and sheets. */
object Radius {
    val sm: Dp = 4.dp
    val md: Dp = 6.dp
    val lg: Dp = 8.dp
    val xl: Dp = 12.dp
}

object IconSize {
    val xs: Dp = 14.dp
    val sm: Dp = 16.dp
    val md: Dp = 20.dp
    val lg: Dp = 24.dp
}

/** Hairline used for every border and divider. */
val Hairline: Dp = 1.dp

private fun ChoiColors.toScheme(): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary, onPrimary = primaryForeground,
        primaryContainer = selection, onPrimaryContainer = foreground,
        inversePrimary = mutedForeground,
        secondary = mutedForeground, onSecondary = background,
        secondaryContainer = accent, onSecondaryContainer = accentForeground,
        tertiary = mutedForeground, onTertiary = background,
        tertiaryContainer = accent, onTertiaryContainer = accentForeground,
        background = background, onBackground = foreground,
        surface = background, onSurface = foreground,
        surfaceVariant = muted, onSurfaceVariant = mutedForeground,
        surfaceTint = Color.Transparent,
        inverseSurface = foreground, inverseOnSurface = background,
        error = destructive, onError = destructiveForeground,
        errorContainer = destructive.copy(alpha = 0.12f), onErrorContainer = destructive,
        outline = if (dark) Zinc.c700 else Zinc.c300, outlineVariant = border,
        scrim = scrim,
        surfaceBright = popover, surfaceDim = muted,
        surfaceContainerLowest = background,
        surfaceContainerLow = background,
        surfaceContainer = popover,
        surfaceContainerHigh = popover,
        surfaceContainerHighest = muted,
    )
}

private val ChoiShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.md),
    small = RoundedCornerShape(Radius.md),
    medium = RoundedCornerShape(Radius.lg),
    large = RoundedCornerShape(Radius.xl),
    extraLarge = RoundedCornerShape(Radius.xl),
)

private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f) =
    TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp)

/** Tight, quiet hierarchy: semibold titles with slight negative tracking, regular body, medium labels. */
private val ChoiTypography = Typography(
    displaySmall = type(30, 36, FontWeight.SemiBold, -0.5f),
    headlineMedium = type(24, 30, FontWeight.SemiBold, -0.4f),
    headlineSmall = type(20, 28, FontWeight.SemiBold, -0.3f),
    titleLarge = type(18, 26, FontWeight.SemiBold, -0.2f),
    titleMedium = type(15, 22, FontWeight.SemiBold, -0.1f),
    titleSmall = type(14, 20, FontWeight.SemiBold),
    bodyLarge = type(15, 22),
    bodyMedium = type(14, 20),
    bodySmall = type(12, 16),
    labelLarge = type(14, 20, FontWeight.Medium),
    labelMedium = type(12, 16, FontWeight.Medium),
    labelSmall = type(11, 14, FontWeight.Medium, 0.1f),
)

private val LocalChoiColors = staticCompositionLocalOf { LightColors }

object ChoiTheme {
    val colors: ChoiColors
        @Composable @ReadOnlyComposable get() = LocalChoiColors.current
}

@Composable
fun ChoiTheme(content: @Composable () -> Unit) {
    // Dynamic (wallpaper) color is deliberately off: the neutral palette is the design.
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    CompositionLocalProvider(LocalChoiColors provides colors) {
        MaterialTheme(colorScheme = colors.toScheme(), shapes = ChoiShapes, typography = ChoiTypography, content = content)
    }
}
