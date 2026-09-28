package com.linroid.ketch.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Accent palettes the apps can be themed with. */
@Serializable
enum class AccentColor {
  @SerialName("signal")
  Signal,

  @SerialName("harbor")
  Harbor,

  @SerialName("fathom")
  Fathom,

  @SerialName("beacon")
  Beacon,
}

/** Whether the apps use a light or dark palette. */
@Serializable
enum class ThemeMode {
  /** Follow the operating system's light/dark setting. */
  @SerialName("system")
  System,

  @SerialName("light")
  Light,

  @SerialName("dark")
  Dark,
}

/**
 * Look and feel settings.
 *
 * Only the apps read this section; the CLI and server ignore it.
 *
 * @property accent accent palette used by the UI.
 * @property theme light/dark mode used by the UI.
 */
@Serializable
data class AppearanceConfig(
  val accent: AccentColor = AccentColor.Signal,
  val theme: ThemeMode = ThemeMode.System,
)
