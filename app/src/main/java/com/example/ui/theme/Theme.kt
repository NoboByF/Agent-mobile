package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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

val LivePilotShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun LivePilotTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StudioLightColorScheme,
        typography = LivePilotTypography,
        shapes = LivePilotShapes,
        content = content
    )
}
