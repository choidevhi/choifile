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
 * Design tokens in one place. The look follows iOS: system colors (white or black backgrounds, grouped lists on
 * a light gray, one blue tint for everything tappable), hairline separators instead of borders, large continuous
 * corners and the iOS type ramp (17 body, 34 large title).
 * Material3 components pick these up through the mapped [ColorScheme], [Shapes] and [Typography];
 * custom pieces read [ChoiTheme.colors], [Space], [Radius] and [IconSize] directly.
 */
private object Ios {
    val blue = Color(0xFF007AFF)
    val blueDark = Color(0xFF0A84FF)
    val red = Color(0xFFFF3B30)
    val redDark = Color(0xFFFF453A)
    val green = Color(0xFF34C759)
    val greenDark = Color(0xFF30D158)
    val gray = Color(0xFF8E8E93)
    val gray2Dark = Color(0xFF636366)
    val gray4 = Color(0xFFD1D1D6)
    val gray5 = Color(0xFFE5E5EA)
    val gray6 = Color(0xFFF2F2F7)
    val gray4Dark = Color(0xFF3A3A3C)
    val gray5Dark = Color(0xFF2C2C2E)
    val gray6Dark = Color(0xFF1C1C1E)
}

/** Semantic colors. Names kept from the earlier shadcn theme so every screen keeps compiling; values are iOS. */
@Immutable
data class ChoiColors(
    /** systemBackground: plain screens and lists. */
    val background: Color,
    val foreground: Color,
    /** secondarySystemGroupedBackground: cells and cards on a grouped screen. */
    val card: Color,
    val popover: Color,
    /** The tint: buttons, links, checks, progress. */
    val primary: Color,
    val primaryForeground: Color,
    /** tertiarySystemFill: gray buttons, chips, icon tiles. */
    val secondary: Color,
    val secondaryForeground: Color,
    val muted: Color,
    /** secondaryLabel. */
    val mutedForeground: Color,
    val accent: Color,
    val accentForeground: Color,
    val destructive: Color,
    val destructiveForeground: Color,
    /** separator. */
    val border: Color,
    val input: Color,
    val ring: Color,
    /** Selected rows and the active tab. */
    val selection: Color,
    val scrim: Color,
    val success: Color,
    /** systemGroupedBackground: behind inset grouped cards (settings-style screens). */
    val groupedBackground: Color,
    val dark: Boolean,
)

private val LightColors = ChoiColors(
    background = Color.White, foreground = Color.Black,
    card = Color.White, popover = Color(0xFFF9F9F9),
    primary = Ios.blue, primaryForeground = Color.White,
    secondary = Color(0xFFEEEEF0), secondaryForeground = Color.Black,
    muted = Ios.gray6, mutedForeground = Ios.gray,
    accent = Ios.gray5, accentForeground = Color.Black,
    destructive = Ios.red, destructiveForeground = Color.White,
    border = Color(0xFFC6C6C8), input = Ios.gray5, ring = Ios.blue,
    selection = Color(0xFFE4EFFD), scrim = Color(0x66000000), success = Ios.green,
    groupedBackground = Ios.gray6,
    dark = false,
)

private val DarkColors = ChoiColors(
    background = Color.Black, foreground = Color.White,
    card = Ios.gray6Dark, popover = Color(0xFF252527),
    primary = Ios.blueDark, primaryForeground = Color.White,
    secondary = Ios.gray5Dark, secondaryForeground = Color.White,
    muted = Ios.gray6Dark, mutedForeground = Color(0xFF8D8D93),
    accent = Ios.gray5Dark, accentForeground = Color.White,
    destructive = Ios.redDark, destructiveForeground = Color.White,
    border = Color(0xFF38383A), input = Ios.gray5Dark, ring = Ios.blueDark,
    selection = Color(0xFF16263D), scrim = Color(0x99000000), success = Ios.greenDark,
    groupedBackground = Color.Black,
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

/** Corner radii (iOS): sm for badges, md for thumbnails and inputs, lg for buttons and cards, xl for alerts and menus. */
object Radius {
    val sm: Dp = 6.dp
    val md: Dp = 8.dp
    val lg: Dp = 12.dp
    val xl: Dp = 14.dp
}

object IconSize {
    val xs: Dp = 14.dp
    val sm: Dp = 16.dp
    val md: Dp = 20.dp
    val lg: Dp = 24.dp
}

/** Hairline used for every separator (iOS draws one physical pixel; half a dp is close on today's screens). */
val Hairline: Dp = 0.5.dp

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
        // Scaffolds default to the grouped gray, so cards stand out the iOS way; plain lists set [background] themselves.
        background = groupedBackground, onBackground = foreground,
        surface = card, onSurface = foreground,
        surfaceVariant = muted, onSurfaceVariant = mutedForeground,
        surfaceTint = Color.Transparent,
        inverseSurface = foreground, inverseOnSurface = background,
        error = destructive, onError = destructiveForeground,
        errorContainer = destructive.copy(alpha = 0.12f), onErrorContainer = destructive,
        outline = border, outlineVariant = border,
        scrim = scrim,
        surfaceBright = popover, surfaceDim = muted,
        surfaceContainerLowest = card,
        surfaceContainerLow = card,
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

/**
 * The iOS type ramp on Material's slots: displaySmall = Large Title, headlineMedium = Title 1, headlineSmall =
 * Title 2, titleLarge = Title 3, titleMedium = Headline, bodyLarge = Body, bodyMedium = Subheadline, bodySmall =
 * Footnote, labelLarge = Callout, labelMedium = Caption 1, labelSmall = Caption 2.
 */
private val ChoiTypography = Typography(
    displaySmall = type(34, 41, FontWeight.Bold, -0.4f),
    headlineMedium = type(28, 34, FontWeight.Bold, -0.3f),
    headlineSmall = type(22, 28, FontWeight.Bold, -0.3f),
    titleLarge = type(20, 25, FontWeight.SemiBold, -0.2f),
    titleMedium = type(17, 22, FontWeight.SemiBold, -0.2f),
    titleSmall = type(15, 20, FontWeight.SemiBold, -0.1f),
    bodyLarge = type(17, 22, tracking = -0.2f),
    bodyMedium = type(15, 20, tracking = -0.1f),
    bodySmall = type(13, 18),
    labelLarge = type(16, 21, FontWeight.Medium, -0.1f),
    labelMedium = type(12, 16, FontWeight.Medium),
    labelSmall = type(11, 13, FontWeight.Medium, 0.05f),
)

private val LocalChoiColors = staticCompositionLocalOf { LightColors }

object ChoiTheme {
    val colors: ChoiColors
        @Composable @ReadOnlyComposable get() = LocalChoiColors.current
}

@Composable
fun ChoiTheme(content: @Composable () -> Unit) {
    // Dynamic (wallpaper) color is deliberately off: the iOS palette is the design.
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    CompositionLocalProvider(LocalChoiColors provides colors) {
        MaterialTheme(colorScheme = colors.toScheme(), shapes = ChoiShapes, typography = ChoiTypography, content = content)
    }
}
