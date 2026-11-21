package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val FintechLightColorScheme = lightColorScheme(
    primary = NavyPrimary,
    onPrimary = PureWhite,
    primaryContainer = NavySurfaceVariant,
    onPrimaryContainer = NavyDeep,
    secondary = EmeraldAccent,
    onSecondary = PureWhite,
    secondaryContainer = EmeraldSoft,
    onSecondaryContainer = EmeraldDarkContainer,
    tertiary = EmeraldBright,
    onTertiary = PureWhite,
    background = AlabasterBg,
    onBackground = TextPrimary,
    surface = SurfaceElevated,
    onSurface = TextPrimary,
    surfaceVariant = NavySurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    outlineVariant = BorderSubtle,
    error = StatusFailed,
    onError = PureWhite,
    errorContainer = StatusFailedBg,
    onErrorContainer = StatusFailed
)

private val FintechDarkColorScheme = darkColorScheme(
    primary = EmeraldBright,
    onPrimary = NavyDeep,
    primaryContainer = NavyContainer,
    onPrimaryContainer = PureWhite,
    secondary = EmeraldAccent,
    onSecondary = PureWhite,
    secondaryContainer = EmeraldDarkContainer,
    onSecondaryContainer = EmeraldSoft,
    tertiary = EmeraldBright,
    onTertiary = NavyDeep,
    background = DarkBg,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkBorder,
    outlineVariant = DarkBorder,
    error = StatusFailed,
    onError = PureWhite,
    errorContainer = StatusFailedBg,
    onErrorContainer = StatusFailed
)

val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MSageerFintechTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) FintechDarkColorScheme else FintechLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
