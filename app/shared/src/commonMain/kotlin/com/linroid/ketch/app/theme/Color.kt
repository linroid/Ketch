package com.linroid.ketch.app.theme

import androidx.compose.ui.graphics.Color
import com.linroid.ketch.api.DownloadState

// Dark scheme — neutral surfaces (hue 250, low chroma) + Signal blue accent.
val KetchBackground = KetchPalette.Dark.bg
val KetchSurface = KetchPalette.Dark.bgElev
val KetchSurfaceVariant = KetchPalette.Dark.panel
val KetchSurfaceContainer = KetchPalette.Dark.panel
val KetchSurfaceContainerHigh = KetchPalette.Dark.bgHover
val KetchOnSurface = KetchPalette.Dark.text
val KetchOnSurfaceVariant = KetchPalette.Dark.textSec
val KetchOutline = KetchPalette.Dark.line
val KetchOutlineVariant = KetchPalette.Dark.lineSoft

// Primary — Signal blue (default accent)
val KetchPrimary = KetchPalette.SignalDark.primary
val KetchPrimaryContainer = KetchPalette.SignalDark.container
val KetchOnPrimary = Color(0xFFFFFFFF)
val KetchOnPrimaryContainer = KetchPalette.SignalDark.onContainer

// Secondary — reuse accent container for tonal surface roles
val KetchSecondary = KetchPalette.SignalDark.onContainer
val KetchSecondaryContainer = KetchPalette.SignalDark.container
val KetchOnSecondary = Color(0xFF000000)
val KetchOnSecondaryContainer = KetchPalette.SignalDark.onContainer

// Tertiary — success/green
val KetchTertiary = KetchPalette.Dark.success
val KetchTertiaryContainer = Color(0xFF003F17)
val KetchOnTertiary = Color(0xFFFFFFFF)
val KetchOnTertiaryContainer = Color(0xFF6FD087)

// Error — danger
val KetchError = KetchPalette.Dark.danger
val KetchErrorContainer = Color(0xFF3A1B1B)
val KetchOnError = Color(0xFFFFFFFF)
val KetchOnErrorContainer = Color(0xFFF3A2A2)

// Light scheme — neutral surfaces + Signal blue accent.
val KetchLightBackground = KetchPalette.Light.bg
val KetchLightSurface = KetchPalette.Light.bgElev
val KetchLightSurfaceVariant = KetchPalette.Light.panel
val KetchLightSurfaceContainer = KetchPalette.Light.panel
val KetchLightSurfaceContainerHigh = KetchPalette.Light.bgHover
val KetchLightOnSurface = KetchPalette.Light.text
val KetchLightOnSurfaceVariant = KetchPalette.Light.textSec
val KetchLightOutline = KetchPalette.Light.line
val KetchLightOutlineVariant = KetchPalette.Light.lineSoft

val KetchLightPrimary = KetchPalette.SignalLight.primary
val KetchLightPrimaryContainer = KetchPalette.SignalLight.container
val KetchLightOnPrimary = Color(0xFFFFFFFF)
val KetchLightOnPrimaryContainer = KetchPalette.SignalLight.onContainer

val KetchLightSecondary = KetchPalette.SignalLight.onContainer
val KetchLightSecondaryContainer = KetchPalette.SignalLight.container
val KetchLightOnSecondary = Color(0xFFFFFFFF)
val KetchLightOnSecondaryContainer = KetchPalette.SignalLight.onContainer

val KetchLightTertiary = KetchPalette.Light.success
val KetchLightTertiaryContainer = Color(0xFFD9F3DD)
val KetchLightOnTertiary = Color(0xFFFFFFFF)
val KetchLightOnTertiaryContainer = Color(0xFF007717)

val KetchLightError = KetchPalette.Light.danger
val KetchLightErrorContainer = Color(0xFFFCE4E5)
val KetchLightOnError = Color(0xFFFFFFFF)
val KetchLightOnErrorContainer = Color(0xFF7E1F20)

data class StateColorPair(
  val foreground: Color,
  val background: Color,
)

data class DownloadStateColors(
  val downloading: StateColorPair,
  val queued: StateColorPair,
  val scheduled: StateColorPair,
  val paused: StateColorPair,
  val completed: StateColorPair,
  val failed: StateColorPair,
  val canceled: StateColorPair,
) {
  fun forState(state: DownloadState): StateColorPair {
    return when (state) {
      is DownloadState.Downloading -> downloading
      is DownloadState.Queued -> queued
      is DownloadState.Scheduled -> scheduled
      is DownloadState.Paused -> paused
      is DownloadState.Completed -> completed
      is DownloadState.Failed -> failed
      is DownloadState.Canceled -> canceled
    }
  }
}

val DarkStateColors = DownloadStateColors(
  downloading = StateColorPair(KetchPalette.SignalDark.primary, KetchPalette.SignalDark.container),
  queued = StateColorPair(KetchPalette.Dark.textSec, KetchPalette.Dark.bgHover),
  scheduled = StateColorPair(KetchPalette.Dark.textSec, KetchPalette.Dark.bgHover),
  paused = StateColorPair(KetchPalette.Dark.warning, Color(0xFF3A2E1B)),
  completed = StateColorPair(KetchPalette.Dark.success, Color(0xFF163A22)),
  failed = StateColorPair(KetchPalette.Dark.danger, Color(0xFF3A1B1B)),
  canceled = StateColorPair(KetchPalette.Dark.textDim, KetchPalette.Dark.bgHover),
)

val LightStateColors = DownloadStateColors(
  downloading = StateColorPair(KetchPalette.SignalLight.primary, KetchPalette.SignalLight.container),
  queued = StateColorPair(KetchPalette.Light.textSec, KetchPalette.Light.bgHover),
  scheduled = StateColorPair(KetchPalette.Light.textSec, KetchPalette.Light.bgHover),
  paused = StateColorPair(KetchPalette.Light.warning, Color(0xFFFFF0D6)),
  completed = StateColorPair(KetchPalette.Light.success, Color(0xFFDCF3E1)),
  failed = StateColorPair(KetchPalette.Light.danger, Color(0xFFFCE4E5)),
  canceled = StateColorPair(KetchPalette.Light.textDim, KetchPalette.Light.bgHover),
)
