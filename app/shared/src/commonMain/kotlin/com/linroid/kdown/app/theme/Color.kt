package com.linroid.kdown.app.theme

import androidx.compose.ui.graphics.Color
import com.linroid.kdown.api.DownloadState

// Surface palette (deep blue-gray)
val KDownBackground = Color(0xFF0F1419)
val KDownSurface = Color(0xFF1A2028)
val KDownSurfaceVariant = Color(0xFF242D38)
val KDownSurfaceContainer = Color(0xFF1E2630)
val KDownSurfaceContainerHigh = Color(0xFF283040)
val KDownOnSurface = Color(0xFFE2E8F0)
val KDownOnSurfaceVariant = Color(0xFF8899AA)
val KDownOutline = Color(0xFF4A5568)
val KDownOutlineVariant = Color(0xFF2D3748)

// Primary accent (teal-blue)
val KDownPrimary = Color(0xFF4FC3F7)
val KDownPrimaryContainer = Color(0xFF1A3A4A)
val KDownOnPrimary = Color(0xFF0F1419)
val KDownOnPrimaryContainer = Color(0xFFB3E5FC)

// Secondary (teal)
val KDownSecondary = Color(0xFF80CBC4)
val KDownSecondaryContainer = Color(0xFF1A3A38)
val KDownOnSecondary = Color(0xFF0F1419)
val KDownOnSecondaryContainer = Color(0xFFB2DFDB)

// Tertiary (success/green)
val KDownTertiary = Color(0xFF66BB6A)
val KDownTertiaryContainer = Color(0xFF1B3A2B)
val KDownOnTertiary = Color(0xFF0F1419)
val KDownOnTertiaryContainer = Color(0xFFA5D6A7)

// Error (red)
val KDownError = Color(0xFFEF5350)
val KDownErrorContainer = Color(0xFF3A1B1B)
val KDownOnError = Color(0xFF0F1419)
val KDownOnErrorContainer = Color(0xFFEF9A9A)

// Light theme surface palette
val KDownLightBackground = Color(0xFFF8FAFC)
val KDownLightSurface = Color(0xFFFFFFFF)
val KDownLightSurfaceVariant = Color(0xFFE2E8F0)
val KDownLightSurfaceContainer = Color(0xFFF1F5F9)
val KDownLightSurfaceContainerHigh = Color(0xFFE2E8F0)
val KDownLightOnSurface = Color(0xFF1A202C)
val KDownLightOnSurfaceVariant = Color(0xFF4A5568)
val KDownLightOutline = Color(0xFF94A3B8)
val KDownLightOutlineVariant = Color(0xFFCBD5E1)

// Light primary
val KDownLightPrimary = Color(0xFF0277BD)
val KDownLightPrimaryContainer = Color(0xFFB3E5FC)
val KDownLightOnPrimary = Color(0xFFFFFFFF)
val KDownLightOnPrimaryContainer = Color(0xFF01579B)

// Light secondary
val KDownLightSecondary = Color(0xFF00796B)
val KDownLightSecondaryContainer = Color(0xFFB2DFDB)
val KDownLightOnSecondary = Color(0xFFFFFFFF)
val KDownLightOnSecondaryContainer = Color(0xFF004D40)

// Light tertiary
val KDownLightTertiary = Color(0xFF2E7D32)
val KDownLightTertiaryContainer = Color(0xFFA5D6A7)
val KDownLightOnTertiary = Color(0xFFFFFFFF)
val KDownLightOnTertiaryContainer = Color(0xFF1B5E20)

// Light error
val KDownLightError = Color(0xFFC62828)
val KDownLightErrorContainer = Color(0xFFEF9A9A)
val KDownLightOnError = Color(0xFFFFFFFF)
val KDownLightOnErrorContainer = Color(0xFF8B0000)

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
