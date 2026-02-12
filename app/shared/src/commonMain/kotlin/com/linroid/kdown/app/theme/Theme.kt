package com.linroid.kdown.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalDownloadStateColors =
  staticCompositionLocalOf { DarkStateColors }

private val KDownDarkColorScheme = darkColorScheme(
  primary = KDownPrimary,
  onPrimary = KDownOnPrimary,
  primaryContainer = KDownPrimaryContainer,
  onPrimaryContainer = KDownOnPrimaryContainer,
  secondary = KDownSecondary,
  onSecondary = KDownOnSecondary,
  secondaryContainer = KDownSecondaryContainer,
  onSecondaryContainer = KDownOnSecondaryContainer,
  tertiary = KDownTertiary,
  onTertiary = KDownOnTertiary,
  tertiaryContainer = KDownTertiaryContainer,
  onTertiaryContainer = KDownOnTertiaryContainer,
  error = KDownError,
  onError = KDownOnError,
  errorContainer = KDownErrorContainer,
  onErrorContainer = KDownOnErrorContainer,
  background = KDownBackground,
  onBackground = KDownOnSurface,
  surface = KDownSurface,
  onSurface = KDownOnSurface,
  surfaceVariant = KDownSurfaceVariant,
  onSurfaceVariant = KDownOnSurfaceVariant,
  surfaceContainerLowest = KDownBackground,
  surfaceContainerLow = KDownSurface,
  surfaceContainer = KDownSurfaceContainer,
  surfaceContainerHigh = KDownSurfaceContainerHigh,
  surfaceContainerHighest = KDownSurfaceVariant,
  outline = KDownOutline,
  outlineVariant = KDownOutlineVariant,
)

private val KDownLightColorScheme = lightColorScheme(
  primary = KDownLightPrimary,
  onPrimary = KDownLightOnPrimary,
  primaryContainer = KDownLightPrimaryContainer,
  onPrimaryContainer = KDownLightOnPrimaryContainer,
  secondary = KDownLightSecondary,
  onSecondary = KDownLightOnSecondary,
  secondaryContainer = KDownLightSecondaryContainer,
  onSecondaryContainer = KDownLightOnSecondaryContainer,
  tertiary = KDownLightTertiary,
  onTertiary = KDownLightOnTertiary,
  tertiaryContainer = KDownLightTertiaryContainer,
  onTertiaryContainer = KDownLightOnTertiaryContainer,
  error = KDownLightError,
  onError = KDownLightOnError,
  errorContainer = KDownLightErrorContainer,
  onErrorContainer = KDownLightOnErrorContainer,
  background = KDownLightBackground,
  onBackground = KDownLightOnSurface,
  surface = KDownLightSurface,
  onSurface = KDownLightOnSurface,
  surfaceVariant = KDownLightSurfaceVariant,
  onSurfaceVariant = KDownLightOnSurfaceVariant,
  surfaceContainerLowest = KDownLightSurface,
  surfaceContainerLow = KDownLightBackground,
  surfaceContainer = KDownLightSurfaceContainer,
  surfaceContainerHigh = KDownLightSurfaceContainerHigh,
  surfaceContainerHighest = KDownLightSurfaceVariant,
  outline = KDownLightOutline,
  outlineVariant = KDownLightOutlineVariant,
)

@Composable
fun KDownTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) {
    KDownDarkColorScheme
  } else {
    KDownLightColorScheme
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
