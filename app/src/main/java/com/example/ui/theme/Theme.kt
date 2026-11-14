package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val JarvisDarkColorScheme = darkColorScheme(
    primary = CobaltPrimary,
    onPrimary = CobaltOnPrimary,
    primaryContainer = CobaltContainer,
    onPrimaryContainer = CobaltOnContainer,
    secondary = CyanSecondary,
    onSecondary = CyanOnSecondary,
    secondaryContainer = CyanContainer,
    onSecondaryContainer = CyanOnContainer,
    tertiary = LavenderTertiary,
    background = ObsidianBg,
    onBackground = TextPrimaryDark,
    surface = ObsidianSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = ObsidianSurfaceContainer,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceContainer,
    surfaceContainerHigh = ObsidianSurfaceContainerHigh,
    outline = ObsidianOutline,
    error = ErrorRedDark
)

private val JarvisLightColorScheme = lightColorScheme(
    primary = CobaltPrimaryLight,
    onPrimary = CobaltOnPrimaryLight,
    primaryContainer = CobaltContainerLight,
    onPrimaryContainer = CobaltOnContainerLight,
    secondary = CyanSecondaryLight,
    onSecondary = CyanOnSecondaryLight,
    tertiary = LavenderTertiary,
    background = PorcelainBg,
    onBackground = TextPrimaryLight,
    surface = PorcelainSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = PorcelainSurfaceContainer,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerLow = PorcelainSurface,
    surfaceContainer = PorcelainSurfaceContainer,
    surfaceContainerHigh = PorcelainSurfaceContainerHigh,
    outline = PorcelainOutline,
    error = ErrorRedLight
)

@Composable
fun JarvisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) JarvisDarkColorScheme else JarvisLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = JarvisTypography,
        content = content
    )
}
