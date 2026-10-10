package com.linroid.ketch.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Accent palettes the apps can be themed with, each named after its color.
 *
 * Saved as [id]. The names older versions saved ([previousId]) still load, and an id this version
 * does not know loads as [Indigo] rather than failing the whole config file.
 *
 * @property id value stored in `config.toml`.
 * @property previousId the name older versions saved this palette under.
 */
@Serializable(with = AccentColorSerializer::class)
enum class AccentColor(val id: String, internal val previousId: String) {
  Indigo(id = "indigo", previousId = "signal"),
  Teal(id = "teal", previousId = "harbor"),
  Green(id = "green", previousId = "fathom"),
  Orange(id = "orange", previousId = "beacon"),
}

internal object AccentColorSerializer : KSerializer<AccentColor> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("com.linroid.ketch.config.AccentColor", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: AccentColor) {
    encoder.encodeString(value.id)
  }

  override fun deserialize(decoder: Decoder): AccentColor {
    val id = decoder.decodeString()
    return AccentColor.entries.firstOrNull { it.id == id || it.previousId == id }
      ?: AccentColor.Indigo
  }
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
 * @property language BCP 47 tag of the language the UI shows, such as "de" or "zh-Hant"; `null`
 *   follows the system. Android 13 and later and iOS keep the choice in their per-app language
 *   setting instead, so the apps leave this unset there.
 */
@Serializable
data class AppearanceConfig(
  val accent: AccentColor = AccentColor.Indigo,
  val theme: ThemeMode = ThemeMode.System,
  val language: String? = null,
)
