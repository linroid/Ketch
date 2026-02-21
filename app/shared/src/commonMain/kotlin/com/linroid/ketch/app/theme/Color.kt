package com.linroid.ketch.app.theme

import androidx.compose.ui.graphics.Color
import com.linroid.ketch.api.DownloadState

// Surface palette (deep blue-gray)
val KetchBackground = Color(0xFF0F1419)
val KetchSurface = Color(0xFF1A2028)
val KetchSurfaceVariant = Color(0xFF242D38)
val KetchSurfaceContainer = Color(0xFF1E2630)
val KetchSurfaceContainerHigh = Color(0xFF283040)
val KetchOnSurface = Color(0xFFE2E8F0)
val KetchOnSurfaceVariant = Color(0xFF8899AA)
val KetchOutline = Color(0xFF4A5568)
val KetchOutlineVariant = Color(0xFF2D3748)

// Primary accent (teal-blue)
val KetchPrimary = Color(0xFF4FC3F7)
val KetchPrimaryContainer = Color(0xFF1A3A4A)
val KetchOnPrimary = Color(0xFF0F1419)
val KetchOnPrimaryContainer = Color(0xFFB3E5FC)

// Secondary (teal)
val KetchSecondary = Color(0xFF80CBC4)
val KetchSecondaryContainer = Color(0xFF1A3A38)
val KetchOnSecondary = Color(0xFF0F1419)
val KetchOnSecondaryContainer = Color(0xFFB2DFDB)

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

// Light theme surface palette
val KetchLightBackground = Color(0xFFF8FAFC)
val KetchLightSurface = Color(0xFFFFFFFF)
val KetchLightSurfaceVariant = Color(0xFFE2E8F0)
val KetchLightSurfaceContainer = Color(0xFFF1F5F9)
val KetchLightSurfaceContainerHigh = Color(0xFFE2E8F0)
val KetchLightOnSurface = Color(0xFF1A202C)
val KetchLightOnSurfaceVariant = Color(0xFF4A5568)
val KetchLightOutline = Color(0xFF94A3B8)
val KetchLightOutlineVariant = Color(0xFFCBD5E1)

// Light primary
val KetchLightPrimary = Color(0xFF0277BD)
val KetchLightPrimaryContainer = Color(0xFFB3E5FC)
val KetchLightOnPrimary = Color(0xFFFFFFFF)
val KetchLightOnPrimaryContainer = Color(0xFF01579B)

// Light secondary
val KetchLightSecondary = Color(0xFF00796B)
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
  val pending: StateColorPair,
  val queued: StateColorPair,
  val scheduled: StateColorPair,
  val paused: StateColorPair,
  val completed: StateColorPair,
  val failed: StateColorPair,
  val canceled: StateColorPair,
  val idle: StateColorPair,
) {
  fun forState(state: DownloadState): StateColorPair {
    return when (state) {
      is DownloadState.Downloading -> downloading
      is DownloadState.Pending -> pending
      is DownloadState.Queued -> queued
      is DownloadState.Scheduled -> scheduled
      is DownloadState.Paused -> paused
      is DownloadState.Completed -> completed
      is DownloadState.Failed -> failed
      is DownloadState.Canceled -> canceled
      is DownloadState.Idle -> idle
    }
  }
}

val DarkStateColors = DownloadStateColors(
  downloading = StateColorPair(
    Color(0xFF4FC3F7), Color(0xFF1B3A4F)
  ),
  pending = StateColorPair(
    Color(0xFF4FC3F7), Color(0xFF1B3A4F)
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
  idle = StateColorPair(
    Color(0xFF78909C), Color(0xFF2A2D35)
  )
)

val LightStateColors = DownloadStateColors(
  downloading = StateColorPair(
    Color(0xFF0277BD), Color(0xFFE1F5FE)
  ),
  pending = StateColorPair(
    Color(0xFF0277BD), Color(0xFFE1F5FE)
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
  idle = StateColorPair(
    Color(0xFF78909C), Color(0xFFECEFF1)
  )
)
