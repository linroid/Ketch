package com.linroid.ketch.app.theme

import androidx.compose.ui.graphics.Color
import com.linroid.ketch.api.DownloadState

// Surface palette (neutral dark)
val KetchBackground = Color(0xFF101010)
val KetchSurface = Color(0xFF1A1A1A)
val KetchSurfaceVariant = Color(0xFF252525)
val KetchSurfaceContainer = Color(0xFF1F1F1F)
val KetchSurfaceContainerHigh = Color(0xFF2A2A2A)
val KetchOnSurface = Color(0xFFE8E8E8)
val KetchOnSurfaceVariant = Color(0xFF999999)
val KetchOutline = Color(0xFF4A4A4A)
val KetchOutlineVariant = Color(0xFF303030)

// Primary (teal — from logo)
val KetchPrimary = Color(0xFF00BCD4)
val KetchPrimaryContainer = Color(0xFF003840)
val KetchOnPrimary = Color(0xFF000000)
val KetchOnPrimaryContainer = Color(0xFFB2EBF2)

// Secondary (deep teal — from logo hull)
val KetchSecondary = Color(0xFF0097A7)
val KetchSecondaryContainer = Color(0xFF002E33)
val KetchOnSecondary = Color(0xFF000000)
val KetchOnSecondaryContainer = Color(0xFF80DEEA)

// Tertiary (success/green)
val KetchTertiary = Color(0xFF66BB6A)
val KetchTertiaryContainer = Color(0xFF1B3A2B)
val KetchOnTertiary = Color(0xFF0F1419)
val KetchOnTertiaryContainer = Color(0xFFA5D6A7)

// Error (red)
val KetchError = Color(0xFFEF5350)
val KetchErrorContainer = Color(0xFF3A1B1B)
val KetchOnError = Color(0xFF0F1419)
val KetchOnErrorContainer = Color(0xFFEF9A9A)

// Light theme surface palette (neutral light)
val KetchLightBackground = Color(0xFFFAFAFA)
val KetchLightSurface = Color(0xFFFFFFFF)
val KetchLightSurfaceVariant = Color(0xFFE8E8E8)
val KetchLightSurfaceContainer = Color(0xFFF2F2F2)
val KetchLightSurfaceContainerHigh = Color(0xFFE8E8E8)
val KetchLightOnSurface = Color(0xFF1A1A1A)
val KetchLightOnSurfaceVariant = Color(0xFF555555)
val KetchLightOutline = Color(0xFF999999)
val KetchLightOutlineVariant = Color(0xFFCCCCCC)

// Light primary (teal — from logo, darker for readability)
val KetchLightPrimary = Color(0xFF00838F)
val KetchLightPrimaryContainer = Color(0xFFB2EBF2)
val KetchLightOnPrimary = Color(0xFFFFFFFF)
val KetchLightOnPrimaryContainer = Color(0xFF006064)

// Light secondary (deep teal)
val KetchLightSecondary = Color(0xFF00695C)
val KetchLightSecondaryContainer = Color(0xFFB2DFDB)
val KetchLightOnSecondary = Color(0xFFFFFFFF)
val KetchLightOnSecondaryContainer = Color(0xFF004D40)

// Light tertiary
val KetchLightTertiary = Color(0xFF2E7D32)
val KetchLightTertiaryContainer = Color(0xFFA5D6A7)
val KetchLightOnTertiary = Color(0xFFFFFFFF)
val KetchLightOnTertiaryContainer = Color(0xFF1B5E20)

// Light error
val KetchLightError = Color(0xFFC62828)
val KetchLightErrorContainer = Color(0xFFEF9A9A)
val KetchLightOnError = Color(0xFFFFFFFF)
val KetchLightOnErrorContainer = Color(0xFF8B0000)

// State-specific color pairs
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
  downloading = StateColorPair(
    Color(0xFF00BCD4), Color(0xFF003840)
  ),
  queued = StateColorPair(
    Color(0xFF90A4AE), Color(0xFF2A2D35)
  ),
  scheduled = StateColorPair(
    Color(0xFF90A4AE), Color(0xFF2A2D35)
  ),
  paused = StateColorPair(
    Color(0xFFFFB74D), Color(0xFF3A2E1B)
  ),
  completed = StateColorPair(
    Color(0xFF66BB6A), Color(0xFF1B3A2B)
  ),
  failed = StateColorPair(
    Color(0xFFEF5350), Color(0xFF3A1B1B)
  ),
  canceled = StateColorPair(
    Color(0xFF78909C), Color(0xFF2A2D35)
  ),
)

val LightStateColors = DownloadStateColors(
  downloading = StateColorPair(
    Color(0xFF00838F), Color(0xFFE0F7FA)
  ),
  queued = StateColorPair(
    Color(0xFF546E7A), Color(0xFFECEFF1)
  ),
  scheduled = StateColorPair(
    Color(0xFF546E7A), Color(0xFFECEFF1)
  ),
  paused = StateColorPair(
    Color(0xFFEF6C00), Color(0xFFFFF3E0)
  ),
  completed = StateColorPair(
    Color(0xFF2E7D32), Color(0xFFE8F5E9)
  ),
  failed = StateColorPair(
    Color(0xFFC62828), Color(0xFFFFEBEE)
  ),
  canceled = StateColorPair(
    Color(0xFF78909C), Color(0xFFECEFF1)
  ),
)
