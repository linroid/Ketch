package com.linroid.ketch.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalDownloadStateColors = staticCompositionLocalOf { DarkStateColors }

val LocalKetchColors = staticCompositionLocalOf<KetchColors> {
  error("KetchColors not provided. Wrap your UI in KetchTheme { … }.")
}

val LocalKetchTypography = staticCompositionLocalOf<KetchTypography> {
  error("KetchTypography not provided. Wrap your UI in KetchTheme { … }.")
}

val LocalKetchShapes = staticCompositionLocalOf<KetchShapes> {
  error("KetchShapes not provided. Wrap your UI in KetchTheme { … }.")
}

val LocalKetchSpacing = staticCompositionLocalOf<KetchSpacing> {
  error("KetchSpacing not provided. Wrap your UI in KetchTheme { … }.")
}

val LocalKetchElevation = staticCompositionLocalOf<KetchElevation> {
  error("KetchElevation not provided. Wrap your UI in KetchTheme { … }.")
}

val LocalKetchMotion = staticCompositionLocalOf<KetchMotion> {
  error("KetchMotion not provided. Wrap your UI in KetchTheme { … }.")
}

@Composable
fun KetchTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  accent: KetchAccent = KetchAccent.Signal,
  colors: KetchColors = if (darkTheme) darkKetchColors(accent) else lightKetchColors(accent),
  typography: KetchTypography = ketchTypography(),
  shapes: KetchShapes = ketchShapes(),
  spacing: KetchSpacing = ketchSpacing(),
  elevation: KetchElevation = if (darkTheme) darkKetchElevation() else lightKetchElevation(),
  motion: KetchMotion = ketchMotion(),
  content: @Composable () -> Unit,
) {
  val stateColors = if (darkTheme) DarkStateColors else LightStateColors
  CompositionLocalProvider(
    LocalDownloadStateColors provides stateColors,
    LocalKetchColors provides colors,
    LocalKetchTypography provides typography,
    LocalKetchShapes provides shapes,
    LocalKetchSpacing provides spacing,
    LocalKetchElevation provides elevation,
    LocalKetchMotion provides motion,
  ) {
    MaterialTheme(
      colorScheme = colors.toMaterialColorScheme(darkTheme),
      typography = typography.toMaterialTypography(),
      shapes = shapes.toMaterialShapes(),
      content = content,
    )
  }
}

object KetchTheme {
  val colors: KetchColors
    @Composable @ReadOnlyComposable
    get() = LocalKetchColors.current

  val typography: KetchTypography
    @Composable @ReadOnlyComposable
    get() = LocalKetchTypography.current

  val shapes: KetchShapes
    @Composable @ReadOnlyComposable
    get() = LocalKetchShapes.current

  val spacing: KetchSpacing
    @Composable @ReadOnlyComposable
    get() = LocalKetchSpacing.current

  val elevation: KetchElevation
    @Composable @ReadOnlyComposable
    get() = LocalKetchElevation.current

  val motion: KetchMotion
    @Composable @ReadOnlyComposable
    get() = LocalKetchMotion.current
}

internal fun KetchColors.toMaterialColorScheme(dark: Boolean): ColorScheme {
  val base = if (dark) darkColorScheme() else lightColorScheme()
  return base.copy(
    background = background,
    onBackground = onBackground,
    surface = surface,
    surfaceTint = Color.Transparent,
    onSurface = onBackground,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant,
    surfaceContainerLowest = background,
    surfaceContainerLow = surface,
    surfaceContainer = surfaceVariant,
    surfaceContainerHigh = surfaceHover,
    surfaceContainerHighest = surfaceHover,
    outline = outline,
    outlineVariant = outlineVariant,
    primary = primary,
    onPrimary = if (dark) background else Color.White,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = onPrimaryContainer,
    onSecondary = Color.White,
    secondaryContainer = primaryContainer,
    onSecondaryContainer = onPrimaryContainer,
    tertiary = success,
    onTertiary = Color.White,
    error = error,
    onError = Color.White,
    errorContainer = error.copy(alpha = 0.12f),
    onErrorContainer = error,
  )
}

internal fun KetchTypography.toMaterialTypography(): Typography = Typography(
  displayLarge = displayLarge,
  displayMedium = displayMedium,
  displaySmall = displaySmall,
  headlineLarge = displayMedium,
  headlineMedium = displaySmall,
  headlineSmall = displaySmall,
  titleLarge = displaySmall,
  titleMedium = bodyLarge,
  titleSmall = labelMedium,
  bodyLarge = bodyLarge,
  bodyMedium = bodyMedium,
  bodySmall = bodySmall,
  labelLarge = labelLarge,
  labelMedium = labelMedium,
  labelSmall = labelSmall,
)

internal fun KetchShapes.toMaterialShapes(): Shapes = Shapes(
  extraSmall = xs as androidx.compose.foundation.shape.CornerBasedShape,
  small = sm as androidx.compose.foundation.shape.CornerBasedShape,
  medium = md as androidx.compose.foundation.shape.CornerBasedShape,
  large = lg as androidx.compose.foundation.shape.CornerBasedShape,
  extraLarge = xl as androidx.compose.foundation.shape.CornerBasedShape,
)
