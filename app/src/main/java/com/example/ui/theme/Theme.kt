package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.data.AppThemeMode

@Immutable
data class StudioPalette(
    val isDark: Boolean,
    val background: Color,
    val card: Color,
    val surfaceSubtle: Color,
    val outline: Color,
    val inkPrimary: Color,
    val inkSecondary: Color,
    val inkMuted: Color,
    val cobaltAccent: Color,
    val cobaltSoft: Color,
    val emeraldAccent: Color,
    val emeraldSoft: Color,
    val violetAccent: Color,
    val violetSoft: Color,
    val amberAccent: Color,
    val amberSoft: Color,
    val roseAccent: Color,
    val roseSoft: Color,
    val redAccent: Color,
    val redSoft: Color,
    val cyanAccent: Color,
    val cyanSoft: Color
)

private val LightStudioPalette = StudioPalette(
    isDark = false,
    background = StudioBackground,
    card = StudioCard,
    surfaceSubtle = StudioSurfaceSubtle,
    outline = StudioOutline,
    inkPrimary = InkPrimary,
    inkSecondary = InkSecondary,
    inkMuted = InkMuted,
    cobaltAccent = CobaltDark,
    cobaltSoft = CobaltSoft,
    emeraldAccent = EmeraldDark,
    emeraldSoft = EmeraldSoft,
    violetAccent = RoyalViolet,
    violetSoft = VioletSoft,
    amberAccent = AmberDark,
    amberSoft = AmberSoft,
    roseAccent = RoseDark,
    roseSoft = RoseSoft,
    redAccent = SignalRedDark,
    redSoft = RedSoft,
    cyanAccent = CyanTeal,
    cyanSoft = CyanSoft
)

private val DarkStudioPalette = StudioPalette(
    isDark = true,
    background = MidnightBackground,
    card = MidnightCard,
    surfaceSubtle = MidnightSurfaceSubtle,
    outline = MidnightOutline,
    inkPrimary = MidnightInkPrimary,
    inkSecondary = MidnightInkSecondary,
    inkMuted = MidnightInkMuted,
    cobaltAccent = CobaltBright,
    cobaltSoft = CobaltSoftDark,
    emeraldAccent = EmeraldBright,
    emeraldSoft = EmeraldSoftDark,
    violetAccent = RoyalVioletBright,
    violetSoft = VioletSoftDark,
    amberAccent = AmberBright,
    amberSoft = AmberSoftDark,
    roseAccent = RoseBright,
    roseSoft = RoseSoftDark,
    redAccent = SignalRedBright,
    redSoft = RedSoftDark,
    cyanAccent = CyanBright,
    cyanSoft = CyanSoftDark
)

val LocalStudioPalette = staticCompositionLocalOf { LightStudioPalette }

object StudioTheme {
    val colors: StudioPalette
        @Composable get() = LocalStudioPalette.current
}

private val StudioLightColorScheme = lightColorScheme(
    primary = CobaltBlue,
    onPrimary = Color.White,
    primaryContainer = CobaltSoft,
    onPrimaryContainer = CobaltDark,
    secondary = RoyalViolet,
    onSecondary = Color.White,
    secondaryContainer = VioletSoft,
    onSecondaryContainer = RoyalVioletDark,
    tertiary = EmeraldGreen,
    onTertiary = Color.White,
    tertiaryContainer = EmeraldSoft,
    onTertiaryContainer = EmeraldDark,
    background = StudioBackground,
    onBackground = InkPrimary,
    surface = StudioCard,
    onSurface = InkPrimary,
    surfaceVariant = StudioSurface,
    onSurfaceVariant = InkSecondary,
    surfaceContainerLowest = StudioCard,
    surfaceContainerLow = StudioBackground,
    surfaceContainer = StudioSurfaceSubtle,
    surfaceContainerHigh = StudioSurface,
    outline = StudioOutline,
    outlineVariant = StudioOutlineStrong,
    error = SignalRed,
    onError = Color.White,
    errorContainer = RedSoft,
    onErrorContainer = SignalRedDark
)

private val StudioDarkColorScheme = darkColorScheme(
    primary = CobaltBright,
    onPrimary = MidnightBackground,
    primaryContainer = CobaltSoftDark,
    onPrimaryContainer = CobaltBright,
    secondary = RoyalVioletBright,
    onSecondary = MidnightBackground,
    secondaryContainer = VioletSoftDark,
    onSecondaryContainer = RoyalVioletBright,
    tertiary = EmeraldBright,
    onTertiary = MidnightBackground,
    tertiaryContainer = EmeraldSoftDark,
    onTertiaryContainer = EmeraldBright,
    background = MidnightBackground,
    onBackground = MidnightInkPrimary,
    surface = MidnightCard,
    onSurface = MidnightInkPrimary,
    surfaceVariant = MidnightSurface,
    onSurfaceVariant = MidnightInkSecondary,
    surfaceContainerLowest = MidnightBackground,
    surfaceContainerLow = MidnightCard,
    surfaceContainer = MidnightSurface,
    surfaceContainerHigh = MidnightSurfaceSubtle,
    outline = MidnightOutline,
    outlineVariant = MidnightOutline,
    error = SignalRedBright,
    onError = MidnightBackground,
    errorContainer = RedSoftDark,
    onErrorContainer = SignalRedBright
)

val LivePilotShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun LivePilotTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val palette = if (isDark) DarkStudioPalette else LightStudioPalette
    val colorScheme = if (isDark) StudioDarkColorScheme else StudioLightColorScheme

    CompositionLocalProvider(LocalStudioPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LivePilotTypography,
            shapes = LivePilotShapes,
            content = content
        )
    }
}
