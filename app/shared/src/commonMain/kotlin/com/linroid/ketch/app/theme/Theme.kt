package com.linroid.ketch.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalDownloadStateColors =
  staticCompositionLocalOf { DarkStateColors }

private val KetchDarkColorScheme = darkColorScheme(
  primary = KetchPrimary,
  onPrimary = KetchOnPrimary,
  primaryContainer = KetchPrimaryContainer,
  onPrimaryContainer = KetchOnPrimaryContainer,
  secondary = KetchSecondary,
  onSecondary = KetchOnSecondary,
  secondaryContainer = KetchSecondaryContainer,
  onSecondaryContainer = KetchOnSecondaryContainer,
  tertiary = KetchTertiary,
  onTertiary = KetchOnTertiary,
  tertiaryContainer = KetchTertiaryContainer,
  onTertiaryContainer = KetchOnTertiaryContainer,
  error = KetchError,
  onError = KetchOnError,
  errorContainer = KetchErrorContainer,
  onErrorContainer = KetchOnErrorContainer,
  background = KetchBackground,
  onBackground = KetchOnSurface,
  surface = KetchSurface,
  onSurface = KetchOnSurface,
  surfaceVariant = KetchSurfaceVariant,
  onSurfaceVariant = KetchOnSurfaceVariant,
  surfaceContainerLowest = KetchBackground,
  surfaceContainerLow = KetchSurface,
  surfaceContainer = KetchSurfaceContainer,
  surfaceContainerHigh = KetchSurfaceContainerHigh,
  surfaceContainerHighest = KetchSurfaceVariant,
  outline = KetchOutline,
  outlineVariant = KetchOutlineVariant,
)

private val KetchLightColorScheme = lightColorScheme(
  primary = KetchLightPrimary,
  onPrimary = KetchLightOnPrimary,
  primaryContainer = KetchLightPrimaryContainer,
  onPrimaryContainer = KetchLightOnPrimaryContainer,
  secondary = KetchLightSecondary,
  onSecondary = KetchLightOnSecondary,
  secondaryContainer = KetchLightSecondaryContainer,
  onSecondaryContainer = KetchLightOnSecondaryContainer,
  tertiary = KetchLightTertiary,
  onTertiary = KetchLightOnTertiary,
  tertiaryContainer = KetchLightTertiaryContainer,
  onTertiaryContainer = KetchLightOnTertiaryContainer,
  error = KetchLightError,
  onError = KetchLightOnError,
  errorContainer = KetchLightErrorContainer,
  onErrorContainer = KetchLightOnErrorContainer,
  background = KetchLightBackground,
  onBackground = KetchLightOnSurface,
  surface = KetchLightSurface,
  onSurface = KetchLightOnSurface,
  surfaceVariant = KetchLightSurfaceVariant,
  onSurfaceVariant = KetchLightOnSurfaceVariant,
  surfaceContainerLowest = KetchLightSurface,
  surfaceContainerLow = KetchLightBackground,
  surfaceContainer = KetchLightSurfaceContainer,
  surfaceContainerHigh = KetchLightSurfaceContainerHigh,
  surfaceContainerHighest = KetchLightSurfaceVariant,
  outline = KetchLightOutline,
  outlineVariant = KetchLightOutlineVariant,
)

@Composable
fun KetchTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) {
    KetchDarkColorScheme
  } else {
    KetchLightColorScheme
  }
  val stateColors = if (darkTheme) {
    DarkStateColors
  } else {
    LightStateColors
  }
  CompositionLocalProvider(
    LocalDownloadStateColors provides stateColors
  ) {
    MaterialTheme(
      colorScheme = colorScheme,
      content = content,
    )
  }
}
