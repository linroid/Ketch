package com.linroid.ketch.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.linroid.ketch.app.platform.rememberReduceMotion
import com.linroid.ketch.config.DensityMode

@Suppress("DEPRECATION")
@Deprecated("Use KetchTheme.colors.status.")
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

/**
 * Provides the Ketch design tokens, and a Material theme mapped onto them, to [content].
 *
 * @param darkTheme whether to use the dark colors.
 * @param accent the accent palette.
 * @param density the density preference; Auto picks by input method.
 * @param reduceMotion whether motion is reduced; follows the system by default.
 * @param windowChrome space the window's own controls take inside the content; by default the
 *   value an enclosing `LocalWindowChrome` provider set, so a host can provide it around `App`.
 */
@Composable
fun KetchTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  accent: KetchAccent = KetchAccent.Signal,
  density: DensityMode = DensityMode.Auto,
  reduceMotion: Boolean = rememberReduceMotion(),
  windowChrome: WindowChrome = LocalWindowChrome.current,
  content: @Composable () -> Unit,
) {
  DensityHost(density) { resolvedDensity ->
    val colors = remember(darkTheme, accent) {
      if (darkTheme) darkKetchColors(accent) else lightKetchColors(accent)
    }
    val typography = rememberKetchTypography(resolvedDensity)
    val shapes = remember(resolvedDensity) { ketchShapes(resolvedDensity) }
    val elevation = remember(darkTheme) { ketchElevation(darkTheme) }
    val motion = remember(reduceMotion) { ketchMotion(reduceMotion) }
    val stateColors = remember(colors) { colors.toDownloadStateColors() }
    @Suppress("DEPRECATION")
    CompositionLocalProvider(
      LocalDownloadStateColors provides stateColors,
      LocalKetchColors provides colors,
      LocalKetchTypography provides typography,
      LocalKetchShapes provides shapes,
      LocalKetchSpacing provides DefaultSpacing,
      LocalKetchElevation provides elevation,
      LocalKetchMotion provides motion,
      LocalKetchDensity provides resolvedDensity,
      LocalWindowChrome provides windowChrome,
    ) {
      MaterialTheme(
        colorScheme = remember(colors) { colors.toMaterialColorScheme() },
        typography = remember(typography) { typography.toMaterialTypography() },
        shapes = remember(shapes) { shapes.toMaterialShapes() },
        content = content,
      )
    }
  }
}

/** Reads the tokens `KetchTheme` provides. */
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

  /** Control sizes of the current density. */
  val density: KetchDensity
    @Composable @ReadOnlyComposable
    get() = LocalKetchDensity.current

  /** Space the window's own controls take inside the content. */
  val windowChrome: WindowChrome
    @Composable @ReadOnlyComposable
    get() = LocalWindowChrome.current

  /** Whether motion is reduced; [motion] is already still when it is. */
  val reduceMotion: Boolean
    @Composable @ReadOnlyComposable
    get() = LocalKetchMotion.current.reduced
}

private val DefaultSpacing = KetchSpacing()

/** Maps every Material 3 color role, so no baseline color can show through. */
internal fun KetchColors.toMaterialColorScheme(): ColorScheme {
  val completed = status.completed
  val failed = status.failed
  return ColorScheme(
    primary = accent,
    onPrimary = onAccent,
    primaryContainer = accentSoft,
    onPrimaryContainer = accentText,
    inversePrimary = inverseAccent,
    secondary = textSecondary,
    onSecondary = surface,
    secondaryContainer = surfaceSunken,
    onSecondaryContainer = textPrimary,
    tertiary = completed.color,
    onTertiary = Color.White,
    tertiaryContainer = completed.soft,
    onTertiaryContainer = completed.color,
    background = canvas,
    onBackground = textPrimary,
    surface = surface,
    onSurface = textPrimary,
    surfaceVariant = surfaceSunken,
    onSurfaceVariant = textSecondary,
    surfaceTint = Color.Transparent,
    inverseSurface = inverseSurface,
    inverseOnSurface = inverseOnSurface,
    error = failed.color,
    onError = Color.White,
    errorContainer = failed.soft,
    onErrorContainer = failed.color,
    outline = borderStrong,
    outlineVariant = hairline,
    scrim = KetchPalette.ScrimInk,
    surfaceBright = surfaceRaised,
    surfaceDim = surfaceSunken,
    surfaceContainer = surfaceSunken,
    surfaceContainerHigh = surfaceHover,
    surfaceContainerHighest = surfacePressed,
    surfaceContainerLow = surface,
    surfaceContainerLowest = surface,
    primaryFixed = accentSoft,
    primaryFixedDim = accentSoft,
    onPrimaryFixed = accentText,
    onPrimaryFixedVariant = accentText,
    secondaryFixed = surfaceSunken,
    secondaryFixedDim = surfacePressed,
    onSecondaryFixed = textPrimary,
    onSecondaryFixedVariant = textSecondary,
    tertiaryFixed = completed.soft,
    tertiaryFixedDim = completed.soft,
    onTertiaryFixed = completed.color,
    onTertiaryFixedVariant = completed.color,
  )
}

internal fun KetchTypography.toMaterialTypography(): Typography = Typography(
  displayLarge = largeTitle,
  displayMedium = pageTitle,
  displaySmall = titleL,
  headlineLarge = largeTitle,
  headlineMedium = pageTitle,
  headlineSmall = titleL,
  titleLarge = titleL,
  titleMedium = titleM,
  titleSmall = label,
  bodyLarge = body,
  bodyMedium = bodyS,
  bodySmall = caption,
  labelLarge = label,
  labelMedium = labelS,
  labelSmall = eyebrow,
)

internal fun KetchShapes.toMaterialShapes(): Shapes = Shapes(
  extraSmall = xs,
  small = sm,
  medium = md,
  large = lg,
  extraLarge = xl,
)
